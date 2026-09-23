package com.jujin.freeway.ioc;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Module-level event subscriber. Contributed at bind time via
 * {@code binder.contribute(EventSubscriber.class)} and supports ordering.
 *
 * <p>Usage:
 * <pre>{@code
 * // Class-based
 * binder.contribute(EventSubscriber.class)
 *     .add(EventSubscriber.of(PostCreated.class, e -> index(e)));
 *
 * // Named + ordered: the id belongs to the contribution, not the subscriber
 * binder.contribute(EventSubscriber.class)
 *     .add("notify", EventSubscriber.of(PostCreated.class, e -> sendEmail(e)))
 *     .after("index");
 *
 * // String-topic
 * binder.contribute(EventSubscriber.class)
 *     .add(EventSubscriber.of("order.placed", payload -> process(payload)));
 * }</pre>
 *
 * <p><b>Filtering convention:</b> subscribers receive every event of the
 * declared type (or a subtype, via hierarchy dispatch) and filter inside the
 * handler. There is deliberately no predicate-based subscription API —
 * filtering logic would only move, not disappear, at the cost of extra API
 * surface and interaction rules. If filtering must be observable (e.g.
 * counted), track it in the handler via {@code EventBus.stats()} deltas.
 */
public final class EventSubscriber<E> {

    private final Class<E> eventType;
    private final Consumer<E> handler;
    /** Non-null for a string-topic subscriber; null for a class subscriber. */
    private final String topic;

    private EventSubscriber(Class<E> eventType, Consumer<E> handler, String topic) {
        this.eventType = eventType;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.topic = topic;
    }

    /** Class-based subscriber: receives events of {@code eventType} and below. */
    public static <E> EventSubscriber<E> of(
        Class<E> eventType,
        Consumer<E> handler
    ) {
        return new EventSubscriber<>(
            Objects.requireNonNull(eventType, "eventType"), handler, null);
    }

    /**
     * String-topic subscriber: the first parameter is the <b>topic</b>
     * (e.g. {@code "order.placed"}).
     *
     * <p>Naming a subscriber for {@code before/after} ordering is the
     * contribution's job, not the subscriber's — pass the id to
     * {@code Contribution.add(id, value)}.
     */
    public static EventSubscriber<Object> of(
        String topic,
        Consumer<Object> handler
    ) {
        return new EventSubscriber<>(
            Object.class, handler, Objects.requireNonNull(topic, "topic"));
    }

    Class<E> eventType() {
        return eventType;
    }

    Consumer<E> handler() {
        return handler;
    }

    String topic() {
        return topic;
    }
}
