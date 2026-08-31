package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.internal.EventBridgeRegistry;
import com.jujin.freeway.ioc.internal.EventStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Internal dispatch engine for {@link EventBus}. Owns class/topic delivery,
 * subscriber isolation, DeadEvent emission and bridge fan-out.
 */
final class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    private final EventSubscriptionIndex subscriptions;
    private final EventBridgeRegistry bridges;
    private final EventStats stats;
    private final BooleanSupplier isClosed;
    private final Consumer<Object> deadEventPublisher;
    private final Function<Class<?>, String> topicResolver;

    EventDispatcher(
        EventSubscriptionIndex subscriptions,
        EventBridgeRegistry bridges,
        EventStats stats,
        BooleanSupplier isClosed,
        Consumer<Object> deadEventPublisher,
        Function<Class<?>, String> topicResolver
    ) {
        this.subscriptions = subscriptions;
        this.bridges = bridges;
        this.stats = stats;
        this.isClosed = isClosed;
        this.deadEventPublisher = deadEventPublisher;
        this.topicResolver = topicResolver;
    }

    void dispatchClass(Object event, boolean bridgeToMq) {
        if (isClosed.getAsBoolean()) {
            return;
        }
        if (!(event instanceof DeadEvent)) {
            stats.published();
        }
        Class<?> eventType = event.getClass();
        List<Consumer<Object>> moduleHandlers = subscriptions.classHandlers(eventType);
        List<Subscription<?>> runtimeHandlers = subscriptions.runtimeClassSubs(eventType);
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        for (Consumer<Object> handler : moduleHandlers) {
            if (event instanceof EventBus.Stoppable s && s.isStopped()) {
                break;
            }
            deliver(
                () -> handler.accept(event),
                "Event subscriber failed for {}",
                eventType.getSimpleName()
            );
        }

        for (Subscription<?> sub : runtimeHandlers) {
            if (event instanceof EventBus.Stoppable s && s.isStopped()) {
                break;
            }
            deliver(
                () -> sub.dispatch(event),
                "Runtime event subscriber failed for {}",
                eventType.getSimpleName()
            );
        }

        if (!hasSubscribers && !(event instanceof DeadEvent)) {
            stats.deadEvent();
            deadEventPublisher.accept(new DeadEvent(this, event));
        }

        if (bridgeToMq && !(event instanceof DeadEvent)) {
            if (event instanceof EventBus.Stoppable s && s.isStopped()) {
                return;
            }
            if (bridges.isEmpty()) {
                return;
            }
            String topic = topicResolver.apply(eventType);
            String eventId = UUID.randomUUID().toString();
            for (EventBridge bridge : bridges.snapshot()) {
                try {
                    bridge.send(topic, event, EventBridge.Channel.CLASS, eventId);
                } catch (Exception ex) {
                    LOG.warn("Event bridge failed for {}", eventType.getSimpleName(), ex);
                }
            }
        }
    }

    void dispatchTopic(String topic, Object payload, boolean bridgeToMq) {
        if (isClosed.getAsBoolean()) {
            return;
        }
        if (!(payload instanceof DeadEvent)) {
            stats.published();
        }
        List<Consumer<Object>> moduleHandlers = subscriptions.topicHandlers(topic);
        List<Subscription<?>> runtimeHandlers = subscriptions.runtimeTopicSubs(topic);
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        for (Consumer<Object> handler : moduleHandlers) {
            if (payload instanceof EventBus.Stoppable s && s.isStopped()) {
                break;
            }
            deliver(
                () -> handler.accept(payload),
                "Event subscriber failed for topic '{}'",
                topic
            );
        }

        for (Subscription<?> sub : runtimeHandlers) {
            if (payload instanceof EventBus.Stoppable s && s.isStopped()) {
                break;
            }
            deliver(
                () -> sub.dispatch(payload),
                "Runtime event subscriber failed for topic '{}'",
                topic
            );
        }

        if (!hasSubscribers) {
            stats.deadEvent();
            deadEventPublisher.accept(new DeadEvent(this, payload));
        }

        if (bridgeToMq && !(payload instanceof DeadEvent)) {
            if (payload instanceof EventBus.Stoppable s && s.isStopped()) {
                return;
            }
            if (bridges.isEmpty()) {
                return;
            }
            String eventId = UUID.randomUUID().toString();
            for (EventBridge bridge : bridges.snapshot()) {
                try {
                    bridge.send(topic, payload, EventBridge.Channel.TOPIC, eventId);
                } catch (Exception ex) {
                    LOG.warn("Event bridge failed for topic '{}'", topic, ex);
                }
            }
        }
    }

    private void deliver(Runnable delivery, String warnMsg, Object... warnArgs) {
        try {
            delivery.run();
            stats.delivered();
        } catch (Throwable ex) {
            stats.subscriberFailure();
            Object[] args = java.util.Arrays.copyOf(warnArgs, warnArgs.length + 1);
            args[warnArgs.length] = ex;
            LOG.warn(warnMsg, args);
        }
    }
}
