package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.extension.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * In-process event bus with class-based and string-topic subscriptions,
 * optional {@code Defer}-scoped buffering, async dispatch, and an optional
 * external event bridge.
 *
 * <p><b>Delivery semantics:</b> at-most-once, best-effort. A throwing
 * subscriber is isolated (other subscribers still receive the event) and
 * counted in {@link #stats()}; the event is not retried. A failing bridge is
 * similarly isolated. Inside a {@code Defer} scope (e.g. a DB transaction),
 * events are buffered and dispatched only after the scope commits — a
 * rollback discards them. Async dispatch ({@link #publishAsync}) has no
 * ordering guarantee; {@link #publishOrdered} provides a globally ordered
 * channel. Runtime subscribers live until {@link #close()} or explicit
 * {@link #unsubscribe}.
 */
public final class EventBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final Container container;
    private volatile boolean closed;
    private volatile EventBridge bridge;
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

    @Inject
    public EventBus(Container container) {
        this(container, null);
    }

    public EventBus(Container container, EventBridge bridge) {
        this.container = Objects.requireNonNull(container, "container");
        this.bridge = bridge;
    }

    /** Wire an event bridge (Kafka, RabbitMQ, etc.) after construction. */
    public void setEventBridge(EventBridge bridge) {
        requireOpen();
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    // ==================== class-based publish ====================

    /**
     * Publish an event to all class-matched subscribers (module + runtime),
     * then bridge to MQ if configured.
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
            Defer.defer(() -> dispatchEvent(event));
            return;
        }
        dispatchEvent(event);
    }

    private <E> void dispatchEvent(E event) {
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
            try {
                handler.accept(event);
                delivered.increment();
            } catch (Exception ex) {
                subscriberFailures.increment();
                LOG.warn(
                    "Event subscriber failed for {}",
                    eventType.getSimpleName(),
                    ex
                );
            }
        }

        for (Subscription<?> sub : runtimeHandlers) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            try {
                sub.dispatch(event);
                delivered.increment();
            } catch (Exception ex) {
                subscriberFailures.increment();
                LOG.warn(
                    "Runtime event subscriber failed for {}",
                    eventType.getSimpleName(),
                    ex
                );
            }
        }

        if (!hasSubscribers && !(event instanceof DeadEvent)) {
            deadEvents.increment();
            publish(new DeadEvent(this, event));
        }

        if (bridge != null && !(event instanceof DeadEvent)) {
            // A stopped event was short-circuited by its subscribers — it must
            // not leave the process via the bridge.
            if (event instanceof Stoppable s && s.isStopped()) {
                return;
            }
            try {
                bridge.send(resolveTopic(eventType), event);
            } catch (Exception ex) {
                LOG.warn(
                    "Event bridge failed for {}",
                    eventType.getSimpleName(),
                    ex
                );
            }
        }
    }

    // ==================== string-topic publish ====================

    /**
     * Publish a payload on a string topic. Subscribers registered via
     * {@code EventSubscriber.of("topic", handler)} or
     * {@code bus.subscribe("topic", handler)} receive it.
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
            Defer.defer(() -> dispatchTopic(topic, payload));
            return;
        }
        dispatchTopic(topic, payload);
    }

    private void dispatchTopic(String topic, Object payload) {
        if (closed) {
            return;
        }
        published.increment();
        List<Consumer<Object>> moduleHandlers = topicSubscribers(topic);
        List<Subscription<?>> runtimeHandlers = runtimeTopicSubs.getOrDefault(
            topic,
            List.of()
        );
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        for (Consumer<Object> handler : moduleHandlers) {
            try {
                handler.accept(payload);
                delivered.increment();
            } catch (Exception ex) {
                subscriberFailures.increment();
                LOG.warn("Event subscriber failed for topic '{}'", topic, ex);
            }
        }

        for (Subscription<?> sub : runtimeHandlers) {
            try {
                sub.dispatch(payload);
                delivered.increment();
            } catch (Exception ex) {
                subscriberFailures.increment();
                LOG.warn(
                    "Runtime event subscriber failed for topic '{}'",
                    topic,
                    ex
                );
            }
        }

        if (!hasSubscribers) {
            deadEvents.increment();
            publish(new DeadEvent(this, payload));
        }

        if (bridge != null) {
            try {
                bridge.send(topic, payload);
            } catch (Exception ex) {
                LOG.warn("Event bridge failed for topic '{}'", topic, ex);
            }
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
        // Defer.isActive() must be evaluated on THIS thread: the executor
        // thread does not inherit the Defer ScopedValue binding, so the guard
        // inside publish() would see no scope and dispatch before commit.
        if (Defer.isActive()) {
            Defer.defer(() -> executor().execute(() -> publish(event)));
            return;
        }
        executor().execute(() -> publish(event));
    }

    /** Async version of {@link #publish(String, Object)}. */
    public void publishAsync(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        if (Defer.isActive()) {
            Defer.defer(() -> executor().execute(() -> publish(topic, payload)));
            return;
        }
        executor().execute(() -> publish(topic, payload));
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
        if (Defer.isActive()) {
            Defer.defer(() -> orderedExecutor().execute(() -> publish(event)));
            return;
        }
        orderedExecutor().execute(() -> publish(event));
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
        if (sub.topic() != null) {
            List<Subscription<?>> subs = runtimeTopicSubs.get(sub.topic());
            if (subs != null) subs.remove(sub);
        } else {
            List<Subscription<?>> subs = runtimeSubs.get(sub.eventType());
            if (subs != null) subs.remove(sub);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        runtimeSubs.clear();
        runtimeTopicSubs.clear();
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
     * Events that can signal the publisher to stop processing subsequent
     * subscribers. Published by subscriber in a multi-handler chain to
     * short-circuit remaining handlers.
     */
    public interface Stoppable {
        void stop();
        boolean isStopped();
    }
}
