package com.jujin.freeway.ioc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class EventBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final Container container;
    private final EventBridge bridge;
    private final Map<Class<?>, List<Subscription<?>>> runtimeSubs =
        new ConcurrentHashMap<>();

    public EventBus(Container container) {
        this(container, null);
    }

    public EventBus(Container container, EventBridge bridge) {
        this.container = Objects.requireNonNull(container, "container");
        this.bridge = bridge;
    }

    /**
     * Publish an event to all subscribers (module-contributed via
     * {@code contribute(EventSubscriber.class)} + runtime subscriptions),
     * then bridge to external MQ if configured.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <E> void publish(E event) {
        Class<?> eventType = event.getClass();
        String topic = resolveTopic(eventType);
        boolean consumed = false;

        // Module-contributed subscribers
        for (Consumer handler : moduleSubscribers(eventType)) {
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

        // Runtime subscribers
        List<Subscription<?>> subs = runtimeSubs.get(eventType);
        if (subs != null) {
            for (Subscription<?> sub : subs) {
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
        }

        // EventDead for zero subscribers
        if (!consumed && !(event instanceof EventDead)) {
            publish(new EventDead(this, event));
        }

        // Bridge to MQ
        if (bridge != null && topic != null) {
            bridge.send(topic, event);
        }
    }

    /**
     * Add a runtime subscriber. Returns a handle for later unsubscribe.
     */
    public <E> Subscription<E> subscribe(
        Class<E> eventType,
        Consumer<E> handler
    ) {
        Subscription<E> sub = new Subscription<>(eventType, handler);
        runtimeSubs
            .computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
            .add((Subscription) sub);
        return sub;
    }

    /**
     * Remove a runtime subscriber.
     */
    public void unsubscribe(Subscription<?> sub) {
        List<Subscription<?>> subs = runtimeSubs.get(sub.eventType());
        if (subs != null) {
            subs.remove(sub);
        }
    }

    @Override
    public void close() {
        runtimeSubs.clear();
    }

    private List<Consumer> moduleSubscribers(Class<?> eventType) {
        Extension<?> ext = moduleExtension();
        if (ext == null) return List.of();
        List<Consumer> result = new ArrayList<>();
        for (Object entry : ext.all()) {
            if (
                entry instanceof EventSubscriber<?> sub &&
                sub.eventType() == eventType
            ) {
                result.add((Consumer) sub.handler());
            }
        }
        return result;
    }

    private Extension<?> moduleExtension() {
        try {
            return container.get(
                Extension.class,
                EventSubscriber.class.getName()
            );
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String resolveTopic(Class<?> eventType) {
        Topic topic = eventType.getAnnotation(Topic.class);
        return topic != null ? topic.value() : eventType.getSimpleName();
    }

    /**
     * Marker interface for events that support short-circuiting the subscriber chain.
     * Call {@code stop()} from a subscriber to prevent downstream subscribers from executing.
     */
    public interface Stoppable {
        void stop();
        boolean isStopped();
    }
}
