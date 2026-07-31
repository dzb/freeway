package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.scoped.Defer;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.extension.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class EventBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final Container container;
    private volatile boolean closed;
    private volatile EventBridge bridge;
    private volatile Executor asyncExecutor;
    private volatile ExecutorService defaultAsyncExecutor;
    private volatile Map<Class<?>, List<Consumer<Object>>> moduleClassIndex;
    private volatile Map<String, List<Consumer<Object>>> moduleTopicIndex;
    private volatile long moduleIndexVersion = -1;
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
        Class<?> eventType = event.getClass();
        List<Consumer<Object>> moduleHandlers = classSubscribers(eventType);
        List<Subscription<?>> runtimeHandlers = runtimeSubs.getOrDefault(
            eventType,
            List.of()
        );
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        for (Consumer<Object> handler : moduleHandlers) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            try {
                handler.accept(event);
            } catch (Exception ex) {
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
            } catch (Exception ex) {
                LOG.warn(
                    "Runtime event subscriber failed for {}",
                    eventType.getSimpleName(),
                    ex
                );
            }
        }

        if (!hasSubscribers && !(event instanceof DeadEvent)) {
            publish(new DeadEvent(this, event));
        }

        if (bridge != null && !(event instanceof DeadEvent)) {
            bridge.send(resolveTopic(eventType), event);
        }
    }

    // ==================== string-topic publish ====================

    /**
     * Publish a payload on a string topic. Subscribers registered via
     * {@code EventSubscriber.of("topic", handler)} or
     * {@code bus.subscribe("topic", handler)} receive it.
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
        List<Consumer<Object>> moduleHandlers = topicSubscribers(topic);
        List<Subscription<?>> runtimeHandlers = runtimeTopicSubs.getOrDefault(
            topic,
            List.of()
        );
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        for (Consumer<Object> handler : moduleHandlers) {
            try {
                handler.accept(payload);
            } catch (Exception ex) {
                LOG.warn("Event subscriber failed for topic '{}'", topic, ex);
            }
        }

        for (Subscription<?> sub : runtimeHandlers) {
            try {
                sub.dispatch(payload);
            } catch (Exception ex) {
                LOG.warn(
                    "Runtime event subscriber failed for topic '{}'",
                    topic,
                    ex
                );
            }
        }

        if (!hasSubscribers) {
            publish(new DeadEvent(this, payload));
        }

        if (bridge != null) {
            bridge.send(topic, payload);
        }
    }

    // ==================== async ====================

    /** Set a custom executor for async dispatch. Defaults to virtual threads. */
    public void setAsyncExecutor(Executor executor) {
        requireOpen();
        this.asyncExecutor = Objects.requireNonNull(executor, "executor");
    }

    private synchronized Executor executor() {
        Executor e = asyncExecutor;
        if (e != null) return e;
        if (defaultAsyncExecutor == null) {
            defaultAsyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
        }
        return defaultAsyncExecutor;
    }

    /** Async version of {@link #publish(Object)}. */
    public <E> void publishAsync(E event) {
        Objects.requireNonNull(event, "event");
        requireOpen();
        executor().execute(() -> publish(event));
    }

    /** Async version of {@link #publish(String, Object)}. */
    public void publishAsync(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        requireOpen();
        executor().execute(() -> publish(topic, payload));
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
        moduleClassIndex = null;
        moduleTopicIndex = null;
        moduleIndexVersion = -1;
        if (defaultAsyncExecutor != null) {
            try {
                defaultAsyncExecutor.close();
            } catch (RuntimeException e) {
                LOG.warn("Failed to close default async executor", e);
            }
            defaultAsyncExecutor = null;
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
        List<Consumer<Object>> subs = moduleClassIndex.get(eventType);
        return subs != null ? subs : List.of();
    }

    private List<Consumer<Object>> topicSubscribers(String topic) {
        ensureIndexed();
        List<Consumer<Object>> subs = moduleTopicIndex.get(topic);
        return subs != null ? subs : List.of();
    }

    private synchronized void ensureIndexed() {
        Extension<?> ext = container.extension(EventSubscriber.class);
        long version = ext.version();
        if (moduleClassIndex != null && moduleIndexVersion == version) {
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
        moduleClassIndex = classIdx;
        moduleTopicIndex = topicIdx;
        moduleIndexVersion = version;
    }

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
