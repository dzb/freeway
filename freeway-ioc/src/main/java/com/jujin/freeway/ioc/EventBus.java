package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.extension.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;


import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-process event bus with class-based and string-topic subscriptions,
 * optional {@code Defer}-scoped buffering, async dispatch, reactive streams
 * ({@link #stream(Class)}/{@link #stream(String)}, JDK {@link Flow}), and an
 * optional external event bridge.
 *
 * <p><b>The message domain has three channels:</b></p>
 * <ul>
 *   <li>broadcast — {@link #publish}: facts about what happened; topic
 *       grammar is past tense ({@code user.created});</li>
 *   <li>request-reply — {@link CallBus#call}/{@link CallBus#consumer}:
 *       commands and queries; topic grammar is {@code mapping.methodName}
 *       ({@code user.getUser}); lives in its own registry, never bridged
 *       to MQ;</li>
 *   <li>streams — {@link #stream(Class)}/{@link #stream(String)}: a
 *       {@link Flow.Publisher} view over the same subscriptions as
 *       broadcast.</li>
 * </ul>
 * <p>Broadcast and streams share one subscriber registry; calls are a
 * separate world. The grammatical split — facts vs commands — is what
 * keeps the two topic namespaces from tangling.</p>
 *
 * <p>Delivery semantics:</b> at-most-once, best-effort. A throwing
 * subscriber is isolated (other subscribers still receive the event) and
 * counted in {@link #stats()}; the event is not retried. A failing bridge is
 * similarly isolated. Inside a {@code Defer} scope (e.g. a DB transaction),
 * events are buffered and dispatched only after the scope commits — a
 * rollback discards them. Async dispatch ({@link #publishAsync}) has no
 * ordering guarantee; {@link #publishOrdered} provides a globally ordered
 * channel. Runtime subscribers live until {@link #close()} or explicit
 * {@link #unsubscribe}.
 *
 * <p><b>Inbound events:</b> events received from an external source (e.g. an
 * MQ subscriber) must be published via {@link #publishInbound(Object)} /
 * {@link #publishInbound(String, Object)} — they are delivered to local
 * subscribers exactly like {@link #publish}, but are never re-bridged to the
 * external MQ. Re-bridging inbound traffic would loop the event back into
 * the queue and re-dispatch it indefinitely.</p>
 */
public final class EventBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final Container container;
    private final Metrics metrics;
    private final Metrics.Counter cPublished;
    private final Metrics.Counter cDelivered;
    private final Metrics.Counter cSubscriberFailures;
    private final Metrics.Counter cDeadEvents;
    /** Bounded window of recent inbound wire ids; null when dedup is off. */
    private volatile IdWindow inboundIds;
    private volatile boolean closed;

    /** Package-private closed probe for {@link EventStreams} bridges. */
    boolean isBusClosed() {
        return closed;
    }
    private final java.util.concurrent.CopyOnWriteArrayList<EventBridge> bridges =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Executor asyncExecutor;
    private volatile ExecutorService defaultAsyncExecutor;
    /** Globally ordered dispatch channel for {@link #publishOrdered}. */
    private volatile ExecutorService orderedExecutor;
    private final LongAdder published = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder subscriberFailures = new LongAdder();
    private final LongAdder deadEvents = new LongAdder();
    /** Atomic snapshot of the module-contributed subscriber index. */
    private volatile ModuleIndex moduleIndex;
    private final Map<Class<?>, List<Subscription<?>>> runtimeSubs =
        new ConcurrentHashMap<>();
    private final Map<String, List<Subscription<?>>> runtimeTopicSubs =
        new ConcurrentHashMap<>();
    /** Live stream bridges, closed (and detached) on {@link #close()}. */
    private final EventStreams streams = new EventStreams(this);

    @Inject
    public EventBus(Container container) {
        this.container = Objects.requireNonNull(container, "container");
        // Metrics is a container builtin (NoopMetrics by default) — always
        // resolvable; a contributed/primary implementation observes the bus.
        this.metrics = container.get(Metrics.class);
        this.cPublished = metrics.counter("eventbus.published");
        this.cDelivered = metrics.counter("eventbus.delivered");
        this.cSubscriberFailures = metrics.counter("eventbus.subscriber_failures");
        this.cDeadEvents = metrics.counter("eventbus.dead_events");
    }

    /** Adds a bridge alongside existing ones — every bridge receives every
     *  outbound event (design: fan-out to N channels, e.g. WS mesh + Kafka
     *  broker simultaneously).
     *
     *  <p>Idempotent by identity: installing the same instance twice does not
     *  deliver the event twice. Distinct instances stay independent, so two
     *  brokers (or one bridge per channel) still fan out side by side.
     *
     *  @throws IllegalStateException if the bus is closed */
    public void addEventBridge(EventBridge bridge) {
        requireOpen();
        Objects.requireNonNull(bridge, "bridge");
        synchronized (bridges) {
            for (EventBridge installed : bridges) {
                if (installed == bridge) {
                    return;
                }
            }
            bridges.add(bridge);
        }
    }

    /** Detaches a bridge previously installed by {@link #addEventBridge}
     *  (matched by identity). Allowed after {@link #close()} so a module's
     *  stop hook can release its channel during shutdown.
     *
     *  @return {@code true} if the bridge was installed and is now detached */
    public boolean removeEventBridge(EventBridge bridge) {
        Objects.requireNonNull(bridge, "bridge");
        synchronized (bridges) {
            for (int i = 0; i < bridges.size(); i++) {
                if (bridges.get(i) == bridge) {
                    bridges.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    // ==================== class-based publish ====================

    /**
     * Publish an event to all class-matched subscribers (module + runtime),
     * then bridge to MQ if configured.
     *
     * <p>This is the <b>class-event</b> channel: subscribers are matched on
     * the runtime type of {@code event}. In particular,
     * {@code publish("x")} dispatches a {@code String} <em>class event</em> —
     * only subscribers on {@code String.class} (or a supertype) receive it.
     * Topic subscribers registered via {@code subscribe("x", ...)} or
     * {@code EventSubscriber.of("x", ...)} do <em>not</em> receive it. For
     * string-topic semantics use {@link #publish(String, Object)}.
     *
     * <p>If called inside a {@code Defer} scope (e.g. within a DB transaction),
     * the event is buffered and only published after the scope commits.
     * If no scope is active, the event is published immediately.</p>
     */
    public <E> void publish(E event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        // DeadEvent always dispatches immediately — it is a diagnostic
        // event that fires when zero subscribers exist, and must not be
        // re-deferred during drain of committed events.
        if (Defer.isActive() && !(event instanceof DeadEvent)) {
            Defer.defer(() -> dispatchEvent(event, true));
            return;
        }
        dispatchEvent(event, true);
    }

    /**
     * Publish an event received from an external source (e.g. a Kafka
     * subscriber). Delivered to local subscribers exactly like
     * {@link #publish(Object)}, but never re-bridged to the external MQ —
     * inbound events must not loop back into the queue.
     *
     * <p>Respects the active {@code Defer} scope like {@link #publish}.</p>
     */
    public <E> void publishInbound(E event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        if (Defer.isActive() && !(event instanceof DeadEvent)) {
            Defer.defer(() -> dispatchEvent(event, false));
            return;
        }
        dispatchEvent(event, false);
    }

    /**
     * Publish an inbound event together with the wire identity it arrived
     * with — otherwise identical to {@link #publishInbound(Object)}.
     *
     * <p>{@code eventId} is the id the <em>originating</em> bus minted and
     * stamped onto the frame. It is offered to the dedup window when one is
     * enabled ({@link #enableInboundDeduplication}): a node reachable over
     * two transports receives every event once per transport, and because
     * both copies carry the same id the second arrival is recognized and
     * dropped. With no id there is nothing to correlate on, so the event is
     * always delivered. {@code null} degenerates to {@link
     * #publishInbound(Object)}.
     *
     * <p>Transports that stamp an id on the wire should use this form. It is
     * deliberately <em>not</em> an overload of {@code publishInbound}: a
     * {@code (String, String)} call cannot be resolved between {@code
     * publishInbound(E, String)} and the topic-channel {@code
     * publishInbound(String, Object)}, so every such call site would be a
     * compile error.</p>
     */
    public <E> void publishInboundWithId(E event, String eventId) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        if (!claimInbound(eventId)) {
            return; // duplicate — already delivered over another channel
        }
        if (Defer.isActive() && !(event instanceof DeadEvent)) {
            Defer.defer(() -> dispatchEvent(event, false));
            return;
        }
        dispatchEvent(event, false);
    }

    private <E> void dispatchEvent(E event, boolean bridgeToMq) {
        // Events buffered in a Defer scope before close() may drain after
        // close: delivering to module subscribers only (runtime lists are
        // cleared) would be partial, and the zero-subscriber DeadEvent
        // publish would throw requireOpen. Silent no-op is the cleanest
        // post-close semantics.
        if (closed) {
            return;
        }
        if (!(event instanceof DeadEvent)) {
            published.increment();
            cPublished.increment();
        }
        Class<?> eventType = event.getClass();
        List<Consumer<Object>> moduleHandlers = classSubscribers(eventType);
        List<Subscription<?>> runtimeHandlers = matchingSubscriptions(
            runtimeSubs,
            eventType
        );
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        for (Consumer<Object> handler : moduleHandlers) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            deliver(
                () -> handler.accept(event),
                "Event subscriber failed for {}",
                eventType.getSimpleName()
            );
        }

        for (Subscription<?> sub : runtimeHandlers) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            deliver(
                () -> sub.dispatch(event),
                "Runtime event subscriber failed for {}",
                eventType.getSimpleName()
            );
        }

        if (!hasSubscribers && !(event instanceof DeadEvent)) {
            deadEvents.increment();
            cDeadEvents.increment();
            publish(new DeadEvent(this, event));
        }

        if (bridgeToMq && !(event instanceof DeadEvent)) {
            // A stopped event was short-circuited by its subscribers — it must
            // not leave the process via the bridge.
            if (event instanceof Stoppable s && s.isStopped()) {
                return;
            }
            // No bridge installed — skip the topic resolution and the id mint
            // entirely. The common case pays for neither.
            if (bridges.isEmpty()) {
                return;
            }
            // Resolved once, not once per bridge: getAnnotation() on the
            // fan-out hot path must not scale with the bridge count.
            String topic = resolveTopic(eventType);
            // One identity per dispatch, shared by every bridge. Minted here
            // rather than inside each bridge: an event bridged over two
            // transports has to carry ONE id, or the two copies are
            // unrelatable and no consumer can ever dedup them.
            String eventId = UUID.randomUUID().toString();
            for (EventBridge bridge : bridges) {
                try {
                    bridge.send(topic, event, EventBridge.Channel.CLASS, eventId);
                } catch (Exception ex) {
                    LOG.warn(
                        "Event bridge failed for {}",
                        eventType.getSimpleName(),
                        ex
                    );
                }
            }
        }
    }

    // ==================== string-topic publish ====================

    /**
     * Publish a payload on a string topic. Subscribers registered via
     * {@code EventSubscriber.of("topic", handler)} or
     * {@code bus.subscribe("topic", handler)} receive it.
     *
     * <p>This is the <b>topic</b> channel: dispatch matches the topic string,
     * not the payload's class. A single-argument {@code publish("x")}
     * dispatches a {@code String} <em>class event</em> that topic subscribers
     * do <em>not</em> receive — use this two-argument form whenever the
     * topic itself carries the routing meaning.</p>
     *
     * <p>The payload may be {@code null} (signal semantics — the topic
     * itself carries the meaning); the topic must not be null.
     *
     * <p>Like {@link #publish(Object)}, respects the active {@code Defer} scope.</p>
     */
    public void publish(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        if (Defer.isActive()) {
            Defer.defer(() -> dispatchTopic(topic, payload, true));
            return;
        }
        dispatchTopic(topic, payload, true);
    }

    /**
     * Publish a payload received from an external source on a string topic.
     * Delivered to local topic subscribers exactly like
     * {@link #publish(String, Object)}, but never re-bridged to the external
     * MQ — inbound events must not loop back into the queue.
     *
     * <p>Respects the active {@code Defer} scope like {@link #publish}.</p>
     */
    public void publishInbound(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        if (Defer.isActive()) {
            Defer.defer(() -> dispatchTopic(topic, payload, false));
            return;
        }
        dispatchTopic(topic, payload, false);
    }

    /**
     * Topic-channel counterpart of {@link #publishInboundWithId(Object,
     * String)}: identical dispatch, deduped on {@code eventId} when a window
     * is enabled.
     */
    public void publishInboundWithId(String topic, Object payload, String eventId) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        if (!claimInbound(eventId)) {
            return; // duplicate — already delivered over another channel
        }
        if (Defer.isActive()) {
            Defer.defer(() -> dispatchTopic(topic, payload, false));
            return;
        }
        dispatchTopic(topic, payload, false);
    }

    /**
     * Drops inbound events whose wire id has already been claimed, so an
     * event that reaches this node over two transports is delivered once.
     *
     * <p>Off by default: dedup changes delivery semantics and costs memory,
     * so it is a deliberate opt-in rather than a side effect of installing a
     * second transport. {@code capacity} bounds the window — the last
     * {@code capacity} ids are remembered, roughly "how far back two copies
     * of the same event may be spread". Too small a window lets a slow
     * second copy through; too large one costs memory for nothing.
     *
     * <p>Ids are claimed at publish time, so the two copies race freely —
     * whichever arrives first wins and the other is dropped.</p>
     *
     * @param capacity positive bound on the number of remembered ids
     */
    public void enableInboundDeduplication(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        synchronized (this) {
            if (inboundIds == null || inboundIds.capacity() != capacity) {
                inboundIds = new IdWindow(capacity);
            }
        }
    }

    /** Turns deduplication off and releases the window. */
    public void disableInboundDeduplication() {
        synchronized (this) {
            inboundIds = null;
        }
    }

    /** True when {@code eventId} is new to the window (or dedup is off). */
    private boolean claimInbound(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return true; // no identity to correlate on — always deliver
        }
        IdWindow window = inboundIds;
        return window == null || window.claim(eventId);
    }

    /**
     * Insertion-ordered window of the last {@code capacity} inbound ids.
     * Insertion order (not access order) is deliberate: the window answers
     * "have I seen this recently", and re-seeing an id must not extend its
     * life — otherwise a hot id would pin itself in the window forever.
     */
    private static final class IdWindow {
        private final int capacity;
        private final LinkedHashSet<String> seen = new LinkedHashSet<>();

        IdWindow(int capacity) {
            this.capacity = capacity;
        }

        int capacity() {
            return capacity;
        }

        /** @return true if {@code id} was new; false if already present */
        synchronized boolean claim(String id) {
            if (!seen.add(id)) {
                return false;
            }
            if (seen.size() > capacity) {
                Iterator<String> oldest = seen.iterator();
                oldest.next();
                oldest.remove();
            }
            return true;
        }
    }

    private void dispatchTopic(String topic, Object payload, boolean bridgeToMq) {
        if (closed) {
            return;
        }
        if (!(payload instanceof DeadEvent)) {
            published.increment();
            cPublished.increment();
        }
        List<Consumer<Object>> moduleHandlers = topicSubscribers(topic);
        List<Subscription<?>> runtimeHandlers = runtimeTopicSubs.getOrDefault(
            topic,
            List.of()
        );
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        // Stoppable works identically on the topic channel: a payload that
        // carries stop state short-circuits remaining deliveries, matching
        // the event-channel loop below.
        for (Consumer<Object> handler : moduleHandlers) {
            if (payload instanceof Stoppable s && s.isStopped()) break;
            deliver(
                () -> handler.accept(payload),
                "Event subscriber failed for topic '{}'",
                topic
            );
        }

        for (Subscription<?> sub : runtimeHandlers) {
            if (payload instanceof Stoppable s && s.isStopped()) break;
            deliver(
                () -> sub.dispatch(payload),
                "Runtime event subscriber failed for topic '{}'",
                topic
            );
        }

        if (!hasSubscribers) {
            deadEvents.increment();
            cDeadEvents.increment();
            publish(new DeadEvent(this, payload));
        }

        // Mirrors dispatchEvent: a DeadEvent never leaves the process.
        if (bridgeToMq && !(payload instanceof DeadEvent)) {
            // A stopped payload was short-circuited by its subscribers — it
            // must not leave the process via the bridge.
            if (payload instanceof Stoppable s && s.isStopped()) {
                return;
            }
            // No bridge installed — skip the id mint entirely.
            if (bridges.isEmpty()) {
                return;
            }
            // One identity per dispatch, shared by every bridge — see
            // dispatchEvent: the two copies of an event bridged over two
            // transports must be recognizably the same event.
            String eventId = UUID.randomUUID().toString();
            for (EventBridge bridge : bridges) {
                try {
                    bridge.send(topic, payload, EventBridge.Channel.TOPIC, eventId);
                } catch (Exception ex) {
                    LOG.warn("Event bridge failed for topic '{}'", topic, ex);
                }
            }
        }
    }

    /**
     * Runs one subscriber delivery: increments the delivered counters on
     * success, or the failure counters plus a warn log on a throwing
     * subscriber (which is isolated — other subscribers still receive the
     * event). The throwable is appended as the last warn argument so SLF4J
     * reports it as the exception.
     */
    private void deliver(Runnable delivery, String warnMsg, Object... warnArgs) {
        try {
            delivery.run();
            delivered.increment();
            cDelivered.increment();
        } catch (Throwable ex) {
            subscriberFailures.increment();
            cSubscriberFailures.increment();
            Object[] args = Arrays.copyOf(warnArgs, warnArgs.length + 1);
            args[warnArgs.length] = ex;
            LOG.warn(warnMsg, args);
        }
    }

    // ==================== async ====================

    /** Set a custom executor for async dispatch. Defaults to virtual threads. */
    public void setAsyncExecutor(Executor executor) {
        requireOpen();
        this.asyncExecutor = Objects.requireNonNull(executor, "executor");
    }

    private Executor executor() {
        Executor e = asyncExecutor;
        if (e != null) return e;
        Executor d = defaultAsyncExecutor;
        if (d != null) return d;
        synchronized (this) {
            d = defaultAsyncExecutor;
            if (d == null) {
                // A publishAsync that passed requireOpen() before close() must
                // not create a fresh executor after close() nulled the field.
                requireOpen();
                d = defaultAsyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
            }
            return d;
        }
    }

    /** Async version of {@link #publish(Object)}. */
    public <E> void publishAsync(E event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        executeDeferred(this::executor, () -> publish(event));
    }

    /** Async version of {@link #publish(String, Object)}. */
    public void publishAsync(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        executeDeferred(this::executor, () -> publish(topic, payload));
    }

    // ==================== ordered publish ====================

    /**
     * Publishes an event on the globally ordered channel: events submitted
     * here are dispatched strictly in submission order (single-threaded
     * FIFO), so a sequence of ordered events observes a total order. This is
     * the channel for transaction-outbox-style ordering — events published
     * inside one {@code Defer} scope drain in call order and are dispatched
     * in that same order after the scope commits.
     *
     * <p>{@code key} names the ordering domain (e.g. the aggregate id) for
     * documentation and future per-key parallelism; the current
     * implementation is globally serialized, so any two ordered events are
     * ordered regardless of key. Subscriber failures are isolated and
     * counted, never propagated to the submitter.
     */
    public void publishOrdered(Object key, Object event) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(event, "event");
        requireOpen();
        executeDeferred(this::orderedExecutor, () -> publish(event));
    }

    /**
     * Executes {@code publish} on the executor supplied by {@code exec},
     * buffering it in the active {@code Defer} scope when present.
     *
     * <p>{@code Defer.isActive()} must be evaluated on THIS thread: the
     * executor thread does not inherit the Defer ScopedValue binding, so the
     * guard inside {@code publish} would see no scope and dispatch before
     * commit. The executor is resolved lazily via {@code exec} so the
     * deferred path (which may drain after {@code close()}, e.g. a
     * transaction scope draining during shutdown) never touches
     * {@code executor()}/{@code requireOpen()} — a silent no-op matches the
     * sync path's post-close semantics.
     */
    private void executeDeferred(Supplier<Executor> exec, Runnable publish) {
        if (Defer.isActive()) {
            Defer.defer(() -> {
                if (closed) {
                    return;
                }
                exec.get().execute(publish);
            });
            return;
        }
        exec.get().execute(publish);
    }

    private ExecutorService orderedExecutor() {
        ExecutorService e = orderedExecutor;
        if (e != null) {
            return e;
        }
        synchronized (this) {
            e = orderedExecutor;
            if (e == null) {
                requireOpen();
                e = orderedExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().factory()
                );
            }
            return e;
        }
    }

    // ==================== reactive streams (JDK Flow) ====================

    /**
     * Streams class-matched events as a JDK {@link Flow.Publisher} — the
     * reactive-streams contract built into the JDK since 9, no external
     * dependency. The publisher is cold-lazy: the underlying bus
     * subscription is created on the first downstream {@code subscribe},
     * so an unconsumed stream holds nothing.
     *
     * <p>Backpressure: downstream demand is honored via
     * {@link SubmissionPublisher}; a consumer that cannot keep up
     * overflow-drops events (non-blocking) rather than stalling bus
     * dispatch for everyone else. Dropped events are logged at debug level.</p>
     *
     * <p>Lifecycle: any downstream {@code cancel()} ends the whole stream —
     * the bridge detaches from the bus and further subscribers see
     * {@code onError}. Fan out by calling {@code stream()} once per
     * consumer, not by sharing one publisher instance. Events published
     * inside a {@code Defer} scope reach the stream only after the scope
     * commits, like every other subscription. {@link #close()} completes
     * all live streams with {@code onComplete}.</p>
     *
     * <p>Observability: a live stream is a real subscriber — publishing to
     * a streamed topic emits no {@link DeadEvent}, and {@link #stats()}
     * counts one delivery per event per stream regardless of downstream
     * fan-out (SubmissionPublisher fans out inside the bridge).</p>
     *
     * @param eventType event type to match (with supertypes)
     */
    public <E> Flow.Publisher<E> stream(Class<E> eventType) {
        requireOpen();
        return streams.stream(eventType);
    }

    /**
     * Streams payloads on a string topic as a JDK {@link Flow.Publisher}.
     * Matching is exact (the topic channel), mirroring
     * {@link #subscribe(String, Consumer)} semantics.
     *
     * <p>{@code null} payloads are legal on the topic channel but forbidden
     * by the Flow specification — they are skipped by streams. See
     * {@link #stream(Class)} for backpressure and lifecycle semantics.</p>
     *
     * @param topic topic to match exactly
     */
    public Flow.Publisher<Object> stream(String topic) {
        requireOpen();
        return streams.stream(topic);
    }

    // ==================== stats ====================

    /**
     * Immutable snapshot of cumulative dispatch counters.
     *
     * @param published          user-initiated dispatch attempts (DeadEvent
     *                           diagnostics are counted separately, see below;
     *                           post-close silent no-ops are excluded)
     * @param delivered          successful subscriber deliveries (one per subscriber)
     * @param subscriberFailures throwing subscriber executions
     * @param deadEvents         DeadEvent diagnostics emitted for zero-subscriber events
     */
    public record EventBusStats(
        long published,
        long delivered,
        long subscriberFailures,
        long deadEvents
    ) {}

    /**
     * Snapshot of cumulative dispatch counters. Useful for operational
     * observability (e.g. "subscriberFailures &gt; 0 for the last N events").
     */
    public EventBusStats stats() {
        return new EventBusStats(
            published.sum(),
            delivered.sum(),
            subscriberFailures.sum(),
            deadEvents.sum()
        );
    }

    // ==================== class-based runtime subscribe ====================

    public <E> Subscription<E> subscribe(
        Class<E> eventType,
        Consumer<E> handler
    ) {
        requireOpen();
        Subscription<E> sub = new Subscription<>(eventType, handler);
        runtimeSubs
            .computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
            .add(sub);
        return sub;
    }

    // ==================== string-topic runtime subscribe ====================

    public Subscription<Object> subscribe(
        String topic,
        Consumer<Object> handler
    ) {
        requireOpen();
        Subscription<Object> sub = new Subscription<>(
            Object.class,
            handler,
            topic
        );
        runtimeTopicSubs
            .computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>())
            .add(sub);
        return sub;
    }

    // ==================== unsubscribe ====================

    public void unsubscribe(Subscription<?> sub) {
        // computeIfPresent drops the list once it empties — long-running apps
        // subscribe/unsubscribe dynamically and must not accumulate a shell
        // entry (and its COW allocation) per ever-used key.
        if (sub.topic() != null) {
            runtimeTopicSubs.computeIfPresent(sub.topic(), (key, subs) -> {
                subs.remove(sub);
                return subs.isEmpty() ? null : subs;
            });
        } else {
            runtimeSubs.computeIfPresent(sub.eventType(), (key, subs) -> {
                subs.remove(sub);
                return subs.isEmpty() ? null : subs;
            });
        }
    }

    // ==================== subscriber queries ====================

    /**
     * True when at least one subscriber (module-contributed or runtime) is
     * registered for the exact topic channel. The query-side counterpart of
     * {@link CallBus#handles(String)}.
     */
    public boolean hasSubscribers(String topic) {
        requireOpen();
        boolean module = !topicSubscribers(topic).isEmpty();
        return module || !runtimeTopicSubs.getOrDefault(topic, List.of()).isEmpty();
    }

    /**
     * True when at least one subscriber matches the event type, including
     * hierarchy dispatch (subscribers of supertypes count).
     */
    public boolean hasSubscribers(Class<?> eventType) {
        requireOpen();
        List<Consumer<Object>> module = classSubscribers(eventType);
        List<Subscription<?>> runtime = matchingSubscriptions(runtimeSubs, eventType);
        return !module.isEmpty() || !runtime.isEmpty();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Detach every bridge: a closed bus must not keep module channels
        // (and their sockets) reachable, and post-close publishes are
        // best-effort no-ops anyway.
        synchronized (bridges) {
            bridges.clear();
        }
        runtimeTopicSubs.clear();
        // Broadcast semantics: post-close publishes are silent no-ops — a
        // fact nobody consumes must not abort shutdown. The counterpart
        // CallBus takes the opposite stance deliberately: pending calls fail
        // explicitly, because a request-reply caller blocked on join() must
        // never wait forever on a dead bus.
        // Complete live streams so downstream subscribers are not left
        // hanging on a dead bus.
        streams.closeAll();
        moduleIndex = null;
        // Same lock as the lazy init in executor(): prevents a publishAsync
        // that passed requireOpen() from creating a fresh default executor
        // after this method observed null and skipped shutdown.
        synchronized (this) {
            if (defaultAsyncExecutor != null) {
                try {
                    defaultAsyncExecutor.close();
                } catch (RuntimeException e) {
                    LOG.warn("Failed to close default async executor", e);
                }
                defaultAsyncExecutor = null;
            }
            if (orderedExecutor != null) {
                try {
                    orderedExecutor.close();
                } catch (RuntimeException e) {
                    LOG.warn("Failed to close ordered executor", e);
                }
                orderedExecutor = null;
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("EventBus is closed");
        }
    }

    // ==================== internals ====================

    private List<Consumer<Object>> classSubscribers(Class<?> eventType) {
        ensureIndexed();
        ModuleIndex idx = moduleIndex;
        if (idx == null) {
            return List.of();
        }
        return matchingSubscriptions(idx.classIdx(), eventType);
    }

    /**
     * Returns subscriptions for {@code eventType} and every supertype
     * (superclasses and interfaces, excluding {@code Object}): a subscriber
     * declared on a parent type receives events of any subtype, but a
     * subscriber declared on a subtype never receives parent events.
     */
    private static <T> List<T> matchingSubscriptions(
        Map<Class<?>, List<T>> index,
        Class<?> eventType
    ) {
        List<T> direct = index.get(eventType);
        List<T> result = direct != null ? new ArrayList<>(direct) : null;
        for (Class<?> sup : SUPER_TYPES.get(eventType)) {
            List<T> subs = index.get(sup);
            if (subs != null) {
                if (result == null) {
                    result = new ArrayList<>();
                }
                result.addAll(subs);
            }
        }
        return result != null ? result : List.of();
    }

    /**
     * Supertype chain (superclasses + interfaces, transitive, excluding
     * {@code Object} and the type itself), cached per event class. A
     * subscriber on {@code Object.class} receives nothing — subscribing to
     * every event requires an explicit marker type.
     */
    private static final ClassValue<List<Class<?>>> SUPER_TYPES =
        new ClassValue<>() {
            @Override
            protected List<Class<?>> computeValue(Class<?> type) {
                List<Class<?>> result = new ArrayList<>();
                Set<Class<?>> seen = new HashSet<>();
                Deque<Class<?>> queue = new ArrayDeque<>();
                queue.add(type);
                while (!queue.isEmpty()) {
                    Class<?> c = queue.poll();
                    Class<?> sup = c.getSuperclass();
                    if (sup != null && sup != Object.class && seen.add(sup)) {
                        result.add(sup);
                        queue.add(sup);
                    }
                    for (Class<?> iface : c.getInterfaces()) {
                        if (seen.add(iface)) {
                            result.add(iface);
                            queue.add(iface);
                        }
                    }
                }
                return List.copyOf(result);
            }
        };

    private List<Consumer<Object>> topicSubscribers(String topic) {
        ensureIndexed();
        ModuleIndex idx = moduleIndex;
        List<Consumer<Object>> subs = idx != null ? idx.topicIdx().get(topic) : null;
        return subs != null ? subs : List.of();
    }

    /**
     * Rebuilds the module-subscriber index when the contribution version
     * changes. Double-checked: the publish hot path reads the volatile
     * snapshot lock-free and only enters the synchronized block when the
     * index is stale.
     */
    private void ensureIndexed() {
        Extension<?> ext = container.extension(EventSubscriber.class);
        long version = ext.version();
        ModuleIndex idx = moduleIndex;
        if (idx != null && idx.version() == version) {
            return;
        }
        synchronized (this) {
            idx = moduleIndex;
            version = ext.version();
            if (idx != null && idx.version() == version) {
                return;
            }
            var classIdx = new HashMap<Class<?>, List<Consumer<Object>>>();
            var topicIdx = new HashMap<String, List<Consumer<Object>>>();
            for (Object entry : ext.all()) {
                if (!(entry instanceof EventSubscriber<?> sub)) continue;
                Consumer<Object> handler = adapt(sub);
                if (sub.topic() == null) {
                    classIdx
                        .computeIfAbsent(sub.eventType(), k -> new ArrayList<>())
                        .add(handler);
                } else {
                    topicIdx
                        .computeIfAbsent(sub.topic(), k -> new ArrayList<>())
                        .add(handler);
                }
            }
            moduleIndex = new ModuleIndex(classIdx, topicIdx, version);
        }
    }

    /** Immutable snapshot of the module-subscriber index. */
    private record ModuleIndex(
        Map<Class<?>, List<Consumer<Object>>> classIdx,
        Map<String, List<Consumer<Object>>> topicIdx,
        long version
    ) {}

    private static <E> Consumer<Object> adapt(EventSubscriber<E> sub) {
        Class<E> eventType = sub.eventType();
        Consumer<E> handler = sub.handler();
        return event -> handler.accept(eventType.cast(event));
    }

    private static String resolveTopic(Class<?> eventType) {
        Topic topic = eventType.getAnnotation(Topic.class);
        return topic != null ? topic.value() : eventType.getSimpleName();
    }

    /**
     * Events (or topic payloads) that can signal the publisher to stop
     * processing subsequent subscribers. Published by subscriber in a
     * multi-handler chain to short-circuit remaining handlers. Applies to
     * both channels: class events and string-topic payloads — a stopped
     * message is also withheld from the outbound {@link EventBridge}.
     */
    public interface Stoppable {
        void stop();
        boolean isStopped();
    }

    /**
     * Events that carry a partitioning key for cross-JVM ordering.
     *
     * <p>Optional contract: when an event type implements this interface,
     * external event bridges (Kafka, RabbitMQ, ...) use {@link #key()} as the
     * message key, so the broker keeps events of the same aggregate ordered
     * and parallel consumers stay per-key serial. Events that do not
     * implement it are bridged with a null key — no cross-JVM ordering
     * guarantee and no key-based parallelism on the consuming side.</p>
     *
     * <p>Passive contract like {@link Stoppable}: the bus and its bridges
     * only ever read it; the event type opts in with zero coupling.</p>
     */
    public interface Keyed {
        /** Partitioning key — the ordering domain, e.g. the aggregate id. */
        String key();
    }
}
