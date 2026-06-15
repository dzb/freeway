package com.jujin.freeway.ioc;

import com.jujin.freeway.commons.defer.Defer;
import com.jujin.freeway.ioc.annotation.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EventBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final Container container;
    private volatile EventBridge bridge;
    private volatile Executor asyncExecutor;
    private volatile Map<Class<?>, List<Consumer<Object>>> moduleClassIndex;
    private volatile Map<String, List<Consumer<Object>>> moduleTopicIndex;
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
        boolean consumed = false;

        for (Consumer<Object> handler : classSubscribers(eventType)) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            try {
                handler.accept(event);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn(
                    "Event subscriber failed for {}",
                    eventType.getSimpleName(),
                    ex
                );
            }
        }

        for (Subscription<?> sub : runtimeSubs.getOrDefault(
            eventType,
            List.of()
        )) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            try {
                ((Subscription<E>) sub).accept(event);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn(
                    "Runtime event subscriber failed for {}",
                    eventType.getSimpleName(),
                    ex
                );
            }
        }

        if (!consumed && !(event instanceof DeadEvent)) {
            publish(new DeadEvent(this, event));
        }

        if (bridge != null) {
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
        if (Defer.isActive()) {
            Defer.defer(() -> dispatchTopic(topic, payload));
            return;
        }
        dispatchTopic(topic, payload);
    }

    private void dispatchTopic(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        boolean consumed = false;

        for (Consumer<Object> handler : topicSubscribers(topic)) {
            try {
                handler.accept(payload);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn("Event subscriber failed for topic '{}'", topic, ex);
            }
        }

        for (Subscription<?> sub : runtimeTopicSubs.getOrDefault(
            topic,
            List.of()
        )) {
            try {
                @SuppressWarnings("unchecked")
                var s = (Subscription<Object>) sub;
                s.accept(payload);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn(
                    "Runtime event subscriber failed for topic '{}'",
                    topic,
                    ex
                );
            }
        }

        if (!consumed) {
            publish(new DeadEvent(this, payload));
        }

        if (bridge != null) {
            bridge.send(topic, payload);
        }
    }

    // ==================== async ====================

    /** Set a custom executor for async dispatch. Defaults to virtual threads. */
    public void setAsyncExecutor(Executor executor) {
        this.asyncExecutor = Objects.requireNonNull(executor, "executor");
    }

    private Executor executor() {
        Executor e = asyncExecutor;
        return e != null ? e : Executors.newVirtualThreadPerTaskExecutor();
    }

    /** Async version of {@link #publish(Object)}. */
    public <E> void publishAsync(E event) {
        executor().execute(() -> publish(event));
    }

    /** Async version of {@link #publish(String, Object)}. */
    public void publishAsync(String topic, Object payload) {
        executor().execute(() -> publish(topic, payload));
    }

    // ==================== class-based runtime subscribe ====================

    public <E> Subscription<E> subscribe(
        Class<E> eventType,
        Consumer<E> handler
    ) {
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
        runtimeSubs.clear();
        runtimeTopicSubs.clear();
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
        if (moduleClassIndex != null) return;
        Extension<?> ext;
        try {
            ext = container.get(
                Extension.class,
                EventSubscriber.class.getName()
            );
        } catch (IllegalArgumentException e) {
            moduleClassIndex = Map.of();
            moduleTopicIndex = Map.of();
            return;
        }
        var classIdx = new HashMap<Class<?>, List<Consumer<Object>>>();
        var topicIdx = new HashMap<String, List<Consumer<Object>>>();
        for (Object entry : ext.all()) {
            if (!(entry instanceof EventSubscriber<?> sub)) continue;
            if (sub.eventType() != null) {
                @SuppressWarnings("unchecked")
                Consumer<Object> handler = (Consumer<Object>) sub.handler();
                classIdx
                    .computeIfAbsent(sub.eventType(), k -> new ArrayList<>())
                    .add(handler);
            }
            if (sub.topic() != null) {
                @SuppressWarnings("unchecked")
                Consumer<Object> handler = (Consumer<Object>) sub.handler();
                topicIdx
                    .computeIfAbsent(sub.topic(), k -> new ArrayList<>())
                    .add(handler);
            }
        }
        moduleClassIndex = classIdx;
        moduleTopicIndex = topicIdx;
    }

    private static String resolveTopic(Class<?> eventType) {
        Topic topic = eventType.getAnnotation(Topic.class);
        return topic != null ? topic.value() : eventType.getSimpleName();
    }

    public interface Stoppable {
        void stop();
        boolean isStopped();
    }
}
