package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.internal.EventSinkRegistry;
import com.jujin.freeway.ioc.internal.EventStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Internal dispatch engine for {@link EventBus}. Owns class/topic delivery,
 * subscriber isolation, DeadEvent emission and sink fan-out.
 */
final class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    private final EventSubscriptionIndex subscriptions;
    private final EventSinkRegistry sinks;
    private final EventStats stats;
    private final BooleanSupplier isClosed;
    private final Consumer<Object> deadEventPublisher;
    private final Function<Class<?>, String> topicResolver;

    EventDispatcher(
        EventSubscriptionIndex subscriptions,
        EventSinkRegistry sinks,
        EventStats stats,
        BooleanSupplier isClosed,
        Consumer<Object> deadEventPublisher,
        Function<Class<?>, String> topicResolver
    ) {
        this.subscriptions = subscriptions;
        this.sinks = sinks;
        this.stats = stats;
        this.isClosed = isClosed;
        this.deadEventPublisher = deadEventPublisher;
        this.topicResolver = topicResolver;
    }

    void dispatchEvent(Object event, boolean inbound, String eventId) {
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

        deliverTo(event, moduleHandlers, runtimeHandlers, eventType.getSimpleName());

        if (!hasSubscribers && !(event instanceof DeadEvent)) {
            stats.deadEvent();
            deadEventPublisher.accept(new DeadEvent(this, event));
        }

        if (!inbound && !(event instanceof DeadEvent)) {
            if (event instanceof EventBus.Stoppable s && s.isStopped()) {
                return;
            }
            sendToSinks(topicResolver.apply(eventType), event,
                EventSink.Channel.CLASS, eventId, eventType.getSimpleName());
        }
    }

    void dispatchTopic(String topic, Object payload, boolean inbound, String eventId) {
        if (isClosed.getAsBoolean()) {
            return;
        }
        if (!(payload instanceof DeadEvent)) {
            stats.published();
        }
        List<Consumer<Object>> moduleHandlers = subscriptions.topicHandlers(topic);
        List<Subscription<?>> runtimeHandlers = subscriptions.runtimeTopicSubs(topic);
        boolean hasSubscribers = !moduleHandlers.isEmpty() || !runtimeHandlers.isEmpty();

        deliverTo(payload, moduleHandlers, runtimeHandlers, "topic '" + topic + "'");

        if (!hasSubscribers) {
            stats.deadEvent();
            deadEventPublisher.accept(new DeadEvent(this, payload));
        }

        if (!inbound && !(payload instanceof DeadEvent)) {
            if (payload instanceof EventBus.Stoppable s && s.isStopped()) {
                return;
            }
            sendToSinks(topic, payload, EventSink.Channel.TOPIC, eventId, "topic '" + topic + "'");
        }
    }

    private void deliverTo(
        Object payload,
        List<Consumer<Object>> moduleHandlers,
        List<Subscription<?>> runtimeHandlers,
        String label
    ) {
        for (Consumer<Object> handler : moduleHandlers) {
            if (payload instanceof EventBus.Stoppable s && s.isStopped()) {
                break;
            }
            deliver(() -> handler.accept(payload), "Event subscriber failed for {}", label);
        }
        for (Subscription<?> sub : runtimeHandlers) {
            if (payload instanceof EventBus.Stoppable s && s.isStopped()) {
                break;
            }
            deliver(() -> sub.dispatch(payload), "Runtime event subscriber failed for {}", label);
        }
    }

    private void sendToSinks(
        String topic,
        Object payload,
        EventSink.Channel channel,
        String eventId,
        String label
    ) {
        if (sinks.isEmpty()) {
            return;
        }
        for (EventSink sink : sinks.snapshot()) {
            try {
                sink.send(topic, payload, channel, eventId);
            } catch (Exception ex) {
                LOG.warn("Event sink failed for {}", label, ex);
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
