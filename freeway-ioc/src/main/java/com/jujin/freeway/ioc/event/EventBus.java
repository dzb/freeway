package com.jujin.freeway.ioc.event;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.ioc.Container;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-process event bus with class-based and string-topic subscriptions,
 * optional {@code Defer}-scoped buffering, async dispatch, and reactive
 * streams ({@link #stream(Class)}/{@link #stream(String)}, JDK {@link Flow}).
 *
 * <p><b>The message domain has two channels:</b></p>
 * <ul>
 *   <li>broadcast — {@link #publish}: facts about what happened; topic
 *       grammar is past tense ({@code user.created});</li>
 *   <li>streams — {@link #stream(Class)}/{@link #stream(String)}: a
 *       {@link Flow.Publisher} view over the same subscriptions as
 *       broadcast.</li>
 * </ul>
 * <p>Both share one subscriber registry and one topic grammar. A question
 * that expects a reply is not a fact: it is a method call — in-process
 * through the binding table, across processes through freeway-cloud's
 * typed remote invocation.</p>
 *
 * <p><b>The bus is the in-process plane, and knows no other.</b> A fact
 * that must cross a process boundary is published on the plane that owns
 * the boundary — freeway-cloud's {@code CloudEventBus} for the mesh, an MQ
 * client for durable streams. Whether an event leaves this JVM is visible
 * at the call site, never a property of which modules happen to be loaded.</p>
 *
 * <p><b>Delivery semantics:</b> at-most-once, best-effort. A throwing
 * subscriber is isolated (other subscribers still receive the event) and
 * counted in {@link #stats()}; the event is not retried. Inside a
 * {@code Defer} scope (e.g. a DB transaction), events are buffered and
 * dispatched only after the scope commits — a rollback discards them.
 * Async dispatch ({@link #publishAsync}) has no ordering guarantee;
 * {@link #publishOrdered} provides a globally ordered channel. Within one
 * dispatch, module subscribers (composition-time contributions, in their
 * ordering) run first, then runtime subscribers (in subscription order).
 * Runtime subscribers live until {@link #close()} or explicit
 * {@link #unsubscribe}.
 */
public final class EventBus implements AutoCloseable {

    private final EventStats stats;
    private final EventSubscriptionIndex subscriptions;
    private final EventDispatcher dispatcher;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final EventExecutorSupport executors;
    /** Live stream subscriptions, closed (and detached) on {@link #close()}. */
    private final EventStreams streams = new EventStreams(this);

    /** Package-private closed probe for {@link EventStreams} subscriptions. */
    boolean isBusClosed() {
        return closed.get();
    }

    /**
     * A bus over {@code container}'s {@code EventSubscriber} contributions and
     * {@link Metrics}. The container binds its own ({@code container.get(EventBus.class)});
     * {@code Container} is not an injectable type, so the bus is not
     * constructor-injected — it is built by that builtin's factory.
     */
    public EventBus(Container container) {
        Objects.requireNonNull(container, "container");
        // Metrics is a container builtin (NoopMetrics by default) — always
        // resolvable; a contributed/primary implementation observes the bus.
        this.stats = new EventStats(container.get(Metrics.class));
        // The bus is built lazily on first resolution, always after
        // composition; its subscribers arrive as sealed contributions.
        this.subscriptions = new EventSubscriptionIndex(container);
        this.dispatcher = new EventDispatcher(this, subscriptions, stats);
        this.executors = new EventExecutorSupport(this::requireOpen);
    }

    // ==================== class-based publish ====================

    /**
     * Publish an event to all class-matched subscribers (module + runtime).
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
        boolean defer = Defer.isActive() && !(event instanceof DeadEvent);
        deferOrRun(defer, () -> dispatcher.dispatchEvent(event));
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
        deferOrRun(Defer.isActive(), () -> dispatcher.dispatchTopic(topic, payload));
    }

    /**
     * Runs {@code action} inside the active {@code Defer} scope when asked to
     * defer, immediately otherwise — the one seam where a publish waits for
     * a commit.
     */
    private void deferOrRun(boolean defer, Runnable action) {
        if (defer) {
            Defer.defer(action);
        } else {
            action.run();
        }
    }

    // ==================== async ====================

    /**
     * Sets a custom executor for async dispatch. Defaults to lazily-created
     * virtual threads.
     *
     * <p>Ownership: a caller-supplied executor is <b>never</b> closed by the
     * bus — its lifecycle stays with the installer. Only the bus-created
     * defaults are shut down (bounded wait) on {@link #close()}.</p>
     *
     * <p><b>Deliberately a runtime setter, not a contribution.</b> Everything
     * else that used to be installed on the bus at runtime is a sealed
     * composition-time contribution; an executor is the documented exception
     * because it is a <em>swappable operational handle</em> with its own
     * lifecycle — replaceable while the bus runs, owned (and shut down) by
     * whoever created it. Composition-time data cannot express either half of
     * that. Runtime {@code subscribe} is the other documented post-composition
     * operation.</p>
     */
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
     * <p>Ordering is global <em>inside this JVM</em>: any two ordered events
     * are ordered relative to each other here, but the channel makes no
     * promise past it. Subscriber failures are isolated and counted, never
     * propagated to the submitter.
     */
    public void publishOrdered(Object event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        executeDeferred(executors::orderedExecutor, () -> publish(event));
    }

    /**
     * Ordered version of {@link #publish(String, Object)}: topic payloads
     * submitted here dispatch strictly in submission order on the same
     * global ordered channel as {@link #publishOrdered(Object)}.
     */
    public void publishOrdered(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        executeDeferred(executors::orderedExecutor, () -> publish(topic, payload));
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
            if (closed.get()) {
                return;
            }
            try {
                publish.run();
            } catch (IllegalStateException e) {
                // A task that passed requireOpen() before close() must not
                // surface as a spurious async failure after the bus is gone.
                if (!closed.get()) {
                    throw e;
                }
            }
        };
        if (Defer.isActive()) {
            Defer.defer(() -> {
                if (closed.get()) {
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
     * {@link java.util.concurrent.SubmissionPublisher}; a consumer that cannot keep up
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
     * @param streamDrops        events dropped by a slow/absent-demand stream
     *                           consumer (overflow-drop, never blocks dispatch)
     */
    public record EventBusStats(
        long published,
        long delivered,
        long subscriberFailures,
        long deadEvents,
        long streamDrops
    ) {}

    /**
     * Snapshot of cumulative dispatch counters. Useful for operational
     * observability (e.g. "subscriberFailures &gt; 0 for the last N events").
     */
    public EventBusStats stats() {
        return stats.snapshot();
    }

    /** Package-private: {@link EventStreams} counts overflow-drops here. */
    void recordStreamDrop() {
        stats.streamDrop();
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

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        subscriptions.clearRuntime();
        // Broadcast semantics: post-close publishes are silent no-ops — a
        // fact nobody consumes must not abort shutdown.
        // Complete live streams so downstream subscribers are not left
        // hanging on a dead bus.
        streams.closeAll();
        executors.close();
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("EventBus is closed");
        }
    }

    /**
     * Events (or topic payloads) that can signal the publisher to stop
     * processing subsequent subscribers. Published by subscriber in a
     * multi-handler chain to short-circuit remaining handlers. Applies to
     * both channels: class event and string-topic payloads.
     */
    public interface Stoppable {
        void stop();
        boolean isStopped();
    }
}
