package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.extension.Extension;
import com.jujin.freeway.ioc.internal.EventSinkRegistry;
import com.jujin.freeway.ioc.internal.EventExecutorSupport;
import com.jujin.freeway.ioc.internal.EventStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-process event bus with class-based and string-topic subscriptions,
 * optional {@code Defer}-scoped buffering, async dispatch, reactive streams
 * ({@link #stream(Class)}/{@link #stream(String)}, JDK {@link Flow}), and an
 * optional external event sink.
 *
 * <p><b>The message domain has three channels:</b></p>
 * <ul>
 *   <li>broadcast — {@link #publish}: facts about what happened; topic
 *       grammar is past tense ({@code user.created});</li>
 *   <li>request-reply — {@link CallBus#call}/{@link CallBus#consumer}:
 *       commands and queries; topic grammar is {@code mapping.methodName}
 *       ({@code user.getUser}); lives in its own registry, never sent
 *       to MQ;</li>
 *   <li>streams — {@link #stream(Class)}/{@link #stream(String)}: a
 *       {@link Flow.Publisher} view over the same subscriptions as
 *       broadcast.</li>
 * </ul>
 * <p>Broadcast and streams share one subscriber registry; calls are a
 * separate world. The grammatical split — facts vs commands — is what
 * keeps the two topic namespaces from tangling.</p>
 *
 * <p><b>Delivery semantics:</b> at-most-once, best-effort. A throwing
 * subscriber is isolated (other subscribers still receive the event) and
 * counted in {@link #stats()}; the event is not retried. A failing sink is
 * similarly isolated. Inside a {@code Defer} scope (e.g. a DB transaction),
 * events are buffered and dispatched only after the scope commits — a
 * rollback discards them. Async dispatch ({@link #publishAsync}) has no
 * ordering guarantee; {@link #publishOrdered} provides a globally ordered
 * channel. Runtime subscribers live until {@link #close()} or explicit
 * {@link #unsubscribe}.
 *
 * <p><b>Inbound events:</b> events received from an external source (e.g. an
 * MQ subscriber) are injected through the adapter SPI
 * {@link EventBusInbound#publishInbound(Object, String)} /
 * {@link EventBusInbound#publishInbound(String, Object, String)} — they are
 * delivered to local subscribers exactly like {@link #publish}, but are never
 * sent back out to the external MQ. Sending inbound traffic back out would
 * loop the event back into the queue and re-dispatch it indefinitely.</p>
 */
public final class EventBus implements EventBusInbound, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final Container container;
    private final EventStats stats;
    private final EventSinkRegistry sinkRegistry = new EventSinkRegistry();
    private final EventSubscriptionIndex subscriptions;
    private final EventDispatcher dispatcher;
    /** Bounded window of recent inbound wire ids; null when dedup is off. */
    private volatile IdWindow inboundIds;
    private volatile boolean closed;
    private final EventExecutorSupport executors;
    /** Live stream subscriptions, closed (and detached) on {@link #close()}. */
    private final EventStreams streams = new EventStreams(this);

    /** Package-private closed probe for {@link EventStreams} subscriptions. */
    boolean isBusClosed() {
        return closed;
    }

    @Inject
    public EventBus(Container container) {
        this.container = Objects.requireNonNull(container, "container");
        // Metrics is a container builtin (NoopMetrics by default) — always
        // resolvable; a contributed/primary implementation observes the bus.
        this.stats = new EventStats(container.get(Metrics.class));
        this.subscriptions = new EventSubscriptionIndex(container);
        this.dispatcher = new EventDispatcher(
            subscriptions,
            sinkRegistry,
            stats,
            () -> closed,
            this::publish,
            EventBus::resolveTopic
        );
        this.executors = new EventExecutorSupport(() -> {
            requireOpen();
            return true;
        });
    }

    /** Adds a sink alongside existing ones — every sink receives every
     *  outbound event (design: fan-out to N channels, e.g. WS mesh + Kafka
     *  broker simultaneously).
     *
     *  <p>Idempotent by identity: installing the same instance twice does not
     *  deliver the event twice. Distinct instances stay independent, so two
     *  brokers (or one sink per channel) still fan out side by side.
     *
     *  @throws IllegalStateException if the bus is closed */
    public void addEventSink(EventSink sink) {
        requireOpen();
        Objects.requireNonNull(sink, "sink");
        sinkRegistry.add(sink);
    }

    /** Detaches a sink previously installed by {@link #addEventSink}
     *  (matched by identity). Allowed after {@link #close()} so a module's
     *  stop hook can release its channel during shutdown.
     *
     *  @return {@code true} if the sink was installed and is now detached */
    public boolean removeEventSink(EventSink sink) {
        Objects.requireNonNull(sink, "sink");
        return sinkRegistry.remove(sink);
    }

    // ==================== class-based publish ====================

    /**
     * Publish an event to all class-matched subscribers (module + runtime),
     * then send to external sinks if configured.
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
        publishEvent(event, UUID.randomUUID().toString(), false);
    }

    /**
     * Adapter SPI: publishes an event received from an external transport.
     * The {@code eventId} must be the id carried on the wire, so copies of
     * the same event arriving over multiple transports can be deduplicated.
     * Business code should use {@link #publish(Object)} instead.
     */
    @Override
    public <E> void publishInbound(E event, String eventId) {
        publishEvent(event, eventId, true);
    }

    private <E> void publishEvent(E event, String eventId, boolean inbound) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        if (inbound && !claimInbound(eventId)) {
            return; // duplicate — already delivered over another channel
        }
        // DeadEvent always dispatches immediately — it is a diagnostic
        // event that fires when zero subscribers exist, and must not be
        // re-deferred during drain of committed events.
        boolean defer = Defer.isActive() && !(event instanceof DeadEvent);
        deferOrRun(defer, () -> dispatchEvent(event, inbound, eventId));
    }

    private <E> void dispatchEvent(E event, boolean inbound, String eventId) {
        dispatcher.dispatchEvent(event, inbound, eventId);
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
        publishTopic(topic, payload, UUID.randomUUID().toString(), false);
    }

    /**
     * Adapter SPI: publishes a topic payload received from an external
     * transport. The {@code eventId} must be the wire id so copies of the
     * same event can be deduplicated. Business code should use
     * {@link #publish(String, Object)} instead.
     */
    @Override
    public void publishInbound(String topic, Object payload, String eventId) {
        publishTopic(topic, payload, eventId, true);
    }

    private void publishTopic(
        String topic,
        Object payload,
        String eventId,
        boolean inbound
    ) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        if (inbound && !claimInbound(eventId)) {
            return; // duplicate — already delivered over another channel
        }
        deferOrRun(Defer.isActive(), () -> dispatchTopic(topic, payload, inbound, eventId));
    }

    private void dispatchTopic(
        String topic,
        Object payload,
        boolean inbound,
        String eventId
    ) {
        dispatcher.dispatchTopic(topic, payload, inbound, eventId);
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

    private void deferOrRun(boolean defer, Runnable action) {
        if (defer) {
            Defer.defer(action);
        } else {
            action.run();
        }
    }

    // ==================== async ====================

    /** Set a custom executor for async dispatch. Defaults to virtual threads. */
    public void setAsyncExecutor(Executor executor) {
        requireOpen();
        executors.setAsyncExecutor(executor);
    }

    /** Async version of {@link #publish(Object)}. */
    public <E> void publishAsync(E event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        executeDeferred(executors::asyncExecutor, () -> publish(event));
    }

    /** Async version of {@link #publish(String, Object)}. */
    public void publishAsync(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        executeDeferred(executors::asyncExecutor, () -> publish(topic, payload));
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
    public void publishOrdered(Object event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        executeDeferred(executors::orderedExecutor, () -> publish(event));
    }

    /**
     * @deprecated Use {@link #publishOrdered(Object)}. The {@code key}
     * parameter is reserved for future per-key ordering; the current
     * implementation is globally serialized and ignores it.
     */
    @Deprecated
    public void publishOrdered(Object key, Object event) {
        Objects.requireNonNull(key, "key");
        publishOrdered(event);
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
        Runnable guarded = () -> {
            if (closed) {
                return;
            }
            try {
                publish.run();
            } catch (IllegalStateException e) {
                // A task that passed requireOpen() before close() must not
                // surface as a spurious async failure after the bus is gone.
                if (!closed) {
                    throw e;
                }
            }
        };
        if (Defer.isActive()) {
            Defer.defer(() -> {
                if (closed) {
                    return;
                }
                exec.get().execute(guarded);
            });
            return;
        }
        exec.get().execute(guarded);
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
     * the subscription detaches from the bus and further subscribers see
     * {@code onError}. Fan out by calling {@code stream()} once per
     * consumer, not by sharing one publisher instance. Events published
     * inside a {@code Defer} scope reach the stream only after the scope
     * commits, like every other subscription. {@link #close()} completes
     * all live streams with {@code onComplete}.</p>
     *
     * <p>Observability: a live stream is a real subscriber — publishing to
     * a streamed topic emits no {@link DeadEvent}, and {@link #stats()}
     * counts one delivery per event per stream regardless of downstream
     * fan-out (SubmissionPublisher fans out inside the subscription).</p>
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
            stats.publishedCount(),
            stats.deliveredCount(),
            stats.subscriberFailureCount(),
            stats.deadEventCount()
        );
    }

    // ==================== class-based runtime subscribe ====================

    public <E> Subscription<E> subscribe(
        Class<E> eventType,
        Consumer<E> handler
    ) {
        requireOpen();
        return subscriptions.subscribeClass(eventType, handler);
    }

    // ==================== string-topic runtime subscribe ====================

    public Subscription<Object> subscribe(
        String topic,
        Consumer<Object> handler
    ) {
        requireOpen();
        return subscriptions.subscribeTopic(topic, handler);
    }

    // ==================== unsubscribe ====================

    public void unsubscribe(Subscription<?> sub) {
        subscriptions.unsubscribe(sub);
    }

    // ==================== subscriber queries ====================

    /**
     * True when at least one subscriber (module-contributed or runtime) is
     * registered for the exact topic channel. The query-side counterpart of
     * {@link CallBus#handles(String)}.
     */
    public boolean hasSubscribers(String topic) {
        requireOpen();
        return subscriptions.hasTopicSubscribers(topic);
    }

    /**
     * True when at least one subscriber matches the event type, including
     * hierarchy dispatch (subscribers of supertypes count).
     */
    public boolean hasSubscribers(Class<?> eventType) {
        requireOpen();
        return subscriptions.hasClassSubscribers(eventType);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Detach every sink: a closed bus must not keep module channels
        // (and their sockets) reachable, and post-close publishes are
        // best-effort no-ops anyway.
        sinkRegistry.clear();
        subscriptions.clearRuntime();
        // Broadcast semantics: post-close publishes are silent no-ops — a
        // fact nobody consumes must not abort shutdown. The counterpart
        // CallBus takes the opposite stance deliberately: pending calls fail
        // explicitly, because a request-reply caller blocked on join() must
        // never wait forever on a dead bus.
        // Complete live streams so downstream subscribers are not left
        // hanging on a dead bus.
        streams.closeAll();
        executors.close();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("EventBus is closed");
        }
    }

    // ==================== internals ====================

    private static String resolveTopic(Class<?> eventType) {
        Topic topic = eventType.getAnnotation(Topic.class);
        return topic != null ? topic.value() : eventType.getSimpleName();
    }

    /**
     * Events (or topic payloads) that can signal the publisher to stop
     * processing subsequent subscribers. Published by subscriber in a
     * multi-handler chain to short-circuit remaining handlers. Applies to
     * both channels: class events and string-topic payloads — a stopped
     * message is also withheld from the outbound {@link EventSink}.
     */
    public interface Stoppable {
        void stop();
        boolean isStopped();
    }

    /**
     * Events that carry a partitioning key for cross-JVM ordering.
     *
     * <p>Optional contract: when an event type implements this interface,
     * external event sinks (Kafka, RabbitMQ, ...) use {@link #key()} as the
     * message key, so the broker keeps events of the same aggregate ordered
     * and parallel consumers stay per-key serial. Events that do not
     * implement it are sent with a null key — no cross-JVM ordering
     * guarantee and no key-based parallelism on the consuming side.</p>
     *
     * <p>Passive contract like {@link Stoppable}: the bus and its sinks
     * only ever read it; the event type opts in with zero coupling.</p>
     */
    public interface Keyed {
        /** Partitioning key — the ordering domain, e.g. the aggregate id. */
        String key();
    }
}
