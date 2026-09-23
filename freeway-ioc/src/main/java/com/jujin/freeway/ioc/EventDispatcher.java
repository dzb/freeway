package com.jujin.freeway.ioc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Internal dispatch engine for {@link EventBus}. Owns class/topic delivery,
 * subscriber isolation, DeadEvent emission and sink fan-out.
 */
final class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    /** Emission source recorded on {@link DeadEvent} — the bus, not this
     *  internal engine. */
    private final EventBus bus;
    private final EventSubscriptionIndex subscriptions;
    private final EventSinkRegistry sinks;
    private final EventStats stats;
    private final BooleanSupplier isClosed;
    private final Consumer<Object> deadEventPublisher;
    private final Function<Class<?>, String> topicResolver;

    EventDispatcher(
        EventBus bus,
        EventSubscriptionIndex subscriptions,
        EventSinkRegistry sinks,
        EventStats stats,
        BooleanSupplier isClosed,
        Consumer<Object> deadEventPublisher,
        Function<Class<?>, String> topicResolver
    ) {
        this.bus = bus;
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
        EventSubscriptionIndex.ClassSubs subs = subscriptions.classSubs(eventType);
        boolean hasSubscribers = !subs.isEmpty();
        // Lazy: resolved only if a handler or sink actually fails.
        Supplier<String> label = () -> eventType.getSimpleName();

        deliverTo(event, subs.module(), subs.runtime(), label);

        if (!hasSubscribers && !(event instanceof DeadEvent)) {
            stats.deadEvent();
            deadEventPublisher.accept(new DeadEvent(bus, event));
        }

        if (!inbound && !(event instanceof DeadEvent)) {
            if (event instanceof EventBus.Stoppable s && s.isStopped()) {
                return;
            }
            // Guard before evaluating the sink arguments: with no sinks the
            // topic resolution (even cached) and label work must not run.
            if (!sinks.isEmpty()) {
                sendToSinks(topicResolver.apply(eventType), event,
                    EventSink.Channel.CLASS, eventId, label);
            }
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
        Supplier<String> label = () -> "topic '" + topic + "'";

        deliverTo(payload, moduleHandlers, runtimeHandlers, label);

        // Same guard as the CLASS channel: a DeadEvent is the diagnostic for
        // zero subscribers and must never trigger one of its own (re-publishing
        // it as a topic payload would otherwise emit two diagnostics).
        if (!hasSubscribers && !(payload instanceof DeadEvent)) {
            stats.deadEvent();
            deadEventPublisher.accept(new DeadEvent(bus, payload));
        }

        if (!inbound && !(payload instanceof DeadEvent)) {
            if (payload instanceof EventBus.Stoppable s && s.isStopped()) {
                return;
            }
            if (!sinks.isEmpty()) {
                sendToSinks(topic, payload, EventSink.Channel.TOPIC, eventId, label);
            }
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
