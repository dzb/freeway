package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.annotation.Topic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Internal dispatch engine for {@link EventBus}. Owns class/topic delivery,
 * subscriber isolation, DeadEvent emission and sink fan-out — one body for
 * both channels, which differ only in how subscribers are matched and which
 * topic the sinks see.
 */
final class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    /** {@code @Topic} value, or the type's simple name — the sink topic of a
     *  class event, cached per class so dispatch never repeats the reflective
     *  annotation lookup. */
    private static final ClassValue<String> TOPIC_OF = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            Topic topic = type.getAnnotation(Topic.class);
            return topic != null ? topic.value() : type.getSimpleName();
        }
    };

    /** The owner: its closed flag gates dispatch, it re-publishes the
     *  {@link DeadEvent} diagnostic and is named as that diagnostic's source. */
    private final EventBus bus;
    private final EventSubscriptionIndex subscriptions;
    private final EventSinkRegistry sinks;
    private final EventStats stats;

    EventDispatcher(
        EventBus bus,
        EventSubscriptionIndex subscriptions,
        EventSinkRegistry sinks,
        EventStats stats
    ) {
        this.bus = bus;
        this.subscriptions = subscriptions;
        this.sinks = sinks;
        this.stats = stats;
    }

    void dispatchEvent(Object event, boolean inbound, String eventId) {
        // Closed first: a buffered publish draining after close must not
        // touch the (released) subscription index.
        if (bus.isBusClosed()) {
            return;
        }
        EventSubscriptionIndex.ClassSubs subs = subscriptions.classSubs(event.getClass());
        dispatch(event, null, subs.module(), subs.runtime(), inbound, eventId);
    }

    void dispatchTopic(String topic, Object payload, boolean inbound, String eventId) {
        if (bus.isBusClosed()) {
            return;
        }
        dispatch(payload, topic, subscriptions.topicHandlers(topic),
            subscriptions.runtimeTopicSubs(topic), inbound, eventId);
    }

    /**
     * @param topic the string topic, or {@code null} for the class channel
     *              (the sink topic is then derived from the event type)
     */
    private void dispatch(
        Object payload,
        String topic,
        List<Consumer<Object>> moduleHandlers,
        List<Subscription<?>> runtimeHandlers,
        boolean inbound,
        String eventId
    ) {
        // Lazy: resolved only if a handler or sink actually fails.
        Supplier<String> label = topic == null
            ? () -> payload.getClass().getSimpleName()
            : () -> "topic '" + topic + "'";
        // A DeadEvent is the zero-subscriber diagnostic: it is not counted as a
        // publish, never triggers a diagnostic of its own (on either channel —
        // re-publishing it as a topic payload would otherwise emit two) and
        // never leaves the JVM.
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
        if (inbound || payload instanceof EventBus.Stoppable s && s.isStopped()) {
            return;
        }
        // Guard before evaluating the sink arguments: with no sinks the topic
        // resolution (even cached) must not run.
        if (!sinks.isEmpty()) {
            sendToSinks(
                topic != null ? topic : TOPIC_OF.get(payload.getClass()),
                payload,
                topic != null ? EventSink.Channel.TOPIC : EventSink.Channel.CLASS,
                eventId,
                label);
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

    private void sendToSinks(
        String topic,
        Object payload,
        EventSink.Channel channel,
        String eventId,
        Supplier<String> label
    ) {
        if (sinks.isEmpty()) {
            return; // re-check: removeEventSink/clear may have raced the caller's guard
        }
        // Mint here, once per fan-out: every sink shares the id, and a publish
        // that never reaches a sink (no sinks, rollback, inbound) never pays
        // for a UUID. Inbound carries its wire id; the local channel passes null.
        String id = eventId != null ? eventId : UUID.randomUUID().toString();
        for (EventSink sink : sinks.snapshot()) {
            try {
                sink.send(topic, payload, channel, id);
            } catch (Exception ex) {
                stats.sinkFailure();
                LOG.warn("Event sink failed for {}", label.get(), ex);
            }
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
