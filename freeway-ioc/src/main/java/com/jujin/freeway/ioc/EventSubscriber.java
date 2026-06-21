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
 * // Named + ordered
 * binder.contribute(EventSubscriber.class)
 *     .add("notify", EventSubscriber.of(PostCreated.class, e -> sendEmail(e)))
 *     .after("index");
 *
 * // String-topic
 * binder.contribute(EventSubscriber.class)
 *     .add(EventSubscriber.of("order.placed", payload -> process(payload)));
 * }</pre>
 */
public final class EventSubscriber<E> {

    private final Class<E> eventType;
    private final Consumer<E> handler;
    private final String id;
    private final String topic;

    private EventSubscriber(
        Class<E> eventType,
        Consumer<E> handler,
        String id,
        String topic
    ) {
        this.eventType = eventType;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.id = id;
        this.topic = topic;
    }

    /** Class-based subscriber. */
    public static <E> EventSubscriber<E> of(
        Class<E> eventType,
        Consumer<E> handler
    ) {
        return new EventSubscriber<>(
            Objects.requireNonNull(eventType, "eventType"),
            handler,
            null,
            null
        );
    }

    /** Class-based subscriber with id for ordering. */
    public static <E> EventSubscriber<E> of(
        String id,
        Class<E> eventType,
        Consumer<E> handler
    ) {
        return new EventSubscriber<>(
            Objects.requireNonNull(eventType, "eventType"),
            handler,
            Objects.requireNonNull(id, "id"),
            null
        );
    }

    /** String-topic subscriber. */
    public static EventSubscriber<Object> of(
        String topic,
        Consumer<Object> handler
    ) {
        return new EventSubscriber<>(
            Object.class,
            handler,
            null,
            Objects.requireNonNull(topic, "topic")
        );
    }

    /** String-topic subscriber with id for ordering. */
    public static EventSubscriber<Object> of(
        String id,
        String topic,
        Consumer<Object> handler
    ) {
        return new EventSubscriber<>(
            Object.class,
            handler,
            Objects.requireNonNull(id, "id"),
            Objects.requireNonNull(topic, "topic")
        );
    }

    Class<E> eventType() {
        return eventType;
    }

    Consumer<E> handler() {
        return handler;
    }

    String id() {
        return id;
    }

    String topic() {
        return topic;
    }
}
