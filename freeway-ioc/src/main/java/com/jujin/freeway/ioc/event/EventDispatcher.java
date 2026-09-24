package com.jujin.freeway.ioc.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Internal dispatch engine for {@link EventBus}. Owns class/topic delivery,
 * subscriber isolation and DeadEvent emission — one body for both channels,
 * which differ only in how subscribers are matched.
 */
final class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    /** The owner: its closed flag gates dispatch, it re-publishes the
     *  {@link DeadEvent} diagnostic and is named as that diagnostic's source. */
    private final EventBus bus;
    private final EventSubscriptionIndex subscriptions;
    private final EventStats stats;

    EventDispatcher(
        EventBus bus,
        EventSubscriptionIndex subscriptions,
        EventStats stats
    ) {
        this.bus = bus;
        this.subscriptions = subscriptions;
        this.stats = stats;
    }

    void dispatchEvent(Object event) {
        // Closed first: a buffered publish draining after close must not
        // touch the (released) subscription index.
        if (bus.isBusClosed()) {
            return;
        }
        EventSubscriptionIndex.ClassSubs subs = subscriptions.classSubs(event.getClass());
        dispatch(event, null, subs.module(), subs.runtime());
    }

    void dispatchTopic(String topic, Object payload) {
        if (bus.isBusClosed()) {
            return;
        }
        dispatch(payload, topic, subscriptions.topicHandlers(topic),
            subscriptions.runtimeTopicSubs(topic));
    }

    /**
     * @param topic the string topic, or {@code null} for the class channel
     */
    private void dispatch(
        Object payload,
        String topic,
        List<Consumer<Object>> moduleHandlers,
        List<Subscription<?>> runtimeHandlers
    ) {
        // Lazy: resolved only if a handler actually fails.
        Supplier<String> label = topic == null
            ? () -> payload.getClass().getSimpleName()
            : () -> "topic '" + topic + "'";
        // A DeadEvent is the zero-subscriber diagnostic: it is not counted as a
        // publish and never triggers a diagnostic of its own (on either channel —
        // re-publishing it as a topic payload would otherwise emit two).
        boolean diagnostic = payload instanceof DeadEvent;
        if (!diagnostic) {
            stats.published();
        }
        // Before delivery: the runtime lists are live, and a handler that
        // unsubscribes itself must not turn its own delivery into a dead event.
        boolean unheard = moduleHandlers.isEmpty() && runtimeHandlers.isEmpty();
        deliverTo(payload, moduleHandlers, runtimeHandlers, label);
        if (diagnostic) {
            return;
        }
        if (unheard) {
            stats.deadEvent();
            bus.publish(new DeadEvent(bus, payload));
        }
    }

    private void deliverTo(
        Object payload,
        List<Consumer<Object>> moduleHandlers,
        List<Subscription<?>> runtimeHandlers,
        Supplier<String> label
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

    private void deliver(Runnable delivery, String warnMsg, Supplier<String> label) {
        try {
            delivery.run();
            stats.delivered();
        } catch (Throwable ex) {
            stats.subscriberFailure();
            LOG.warn(warnMsg, label.get(), ex);
        }
    }
}
