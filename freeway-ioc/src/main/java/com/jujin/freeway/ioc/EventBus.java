package com.jujin.freeway.ioc;

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
    private final EventBridge bridge;
    private volatile Executor asyncExecutor;
    private volatile Map<Class<?>, List<Consumer>> moduleClassIndex;
    private volatile Map<String, List<Consumer>> moduleTopicIndex;
    private final Map<Class<?>, List<Subscription<?>>> runtimeSubs = new ConcurrentHashMap<>();
    private final Map<String, List<Subscription<?>>> runtimeTopicSubs = new ConcurrentHashMap<>();

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
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <E> void publish(E event) {
        Class<?> eventType = event.getClass();
        boolean consumed = false;

        for (Consumer handler : moduleSubscribers(eventType, null)) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            try {
                handler.accept(event);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn("Event subscriber failed for {}", eventType.getSimpleName(), ex);
            }
        }

        for (Subscription<?> sub : runtimeSubs.getOrDefault(eventType, List.of())) {
            if (event instanceof Stoppable s && s.isStopped()) break;
            try {
                ((Subscription<E>) sub).accept(event);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn("Runtime event subscriber failed for {}", eventType.getSimpleName(), ex);
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
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void publish(String topic, Object payload) {
        Objects.requireNonNull(topic, "topic");
        boolean consumed = false;

        for (Consumer handler : moduleSubscribers(null, topic)) {
            try {
                handler.accept(payload);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn("Event subscriber failed for topic '{}'", topic, ex);
            }
        }

        for (Subscription<?> sub : runtimeTopicSubs.getOrDefault(topic, List.of())) {
            try {
                ((Subscription) sub).accept(payload);
                consumed = true;
            } catch (Exception ex) {
                LOG.warn("Runtime event subscriber failed for topic '{}'", topic, ex);
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

    public <E> Subscription<E> subscribe(Class<E> eventType, Consumer<E> handler) {
        Subscription<E> sub = new Subscription<>(eventType, handler);
        runtimeSubs.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add((Subscription) sub);
        return sub;
    }

    // ==================== string-topic runtime subscribe ====================

    @SuppressWarnings("unchecked")
    public Subscription<Object> subscribe(String topic, Consumer<Object> handler) {
        Subscription<Object> sub = new Subscription<>(Object.class, handler, topic);
        runtimeTopicSubs.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add((Subscription) sub);
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

    @SuppressWarnings("unchecked")
    private List<Consumer> moduleSubscribers(Class<?> eventType, String topic) {
        ensureIndexed();
        if (eventType != null) {
            List<Consumer> subs = moduleClassIndex.get(eventType);
            return subs != null ? subs : List.of();
        }
        List<Consumer> subs = moduleTopicIndex.get(topic);
        return subs != null ? subs : List.of();
    }

    @SuppressWarnings("unchecked")
    private synchronized void ensureIndexed() {
        if (moduleClassIndex != null) return;
        Extension<?> ext;
        try {
            ext = container.get(Extension.class, EventSubscriber.class.getName());
        } catch (IllegalArgumentException e) {
            moduleClassIndex = Map.of();
            moduleTopicIndex = Map.of();
            return;
        }
        var classIdx = new HashMap<Class<?>, List<Consumer>>();
        var topicIdx = new HashMap<String, List<Consumer>>();
        for (Object entry : ext.all()) {
            if (!(entry instanceof EventSubscriber<?> sub)) continue;
            if (sub.eventType() != null) {
                classIdx.computeIfAbsent(sub.eventType(), k -> new ArrayList<>()).add((Consumer) sub.handler());
            }
            if (sub.topic() != null) {
                topicIdx.computeIfAbsent(sub.topic(), k -> new ArrayList<>()).add((Consumer) sub.handler());
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
