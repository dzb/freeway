package com.jujin.freeway.ioc.event;

import java.util.Objects;
import java.util.function.Consumer;

/** Handle for a runtime subscriber, returned by {@link EventBus#subscribe}. */
public final class Subscription<E> implements Consumer<E> {

    private final Class<E> eventType;
    private final Consumer<E> handler;
    /** Non-null for a string-topic subscription; null for a class subscription. */
    private final String topic;

    Subscription(Class<E> eventType, Consumer<E> handler, String topic) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.topic = topic;
    }

    Class<E> eventType() {
        return eventType;
    }

    String topic() {
        return topic;
    }

    @Override
    public void accept(E event) {
        handler.accept(event);
    }

    void dispatch(Object event) {
        handler.accept(eventType.cast(event));
    }
}
