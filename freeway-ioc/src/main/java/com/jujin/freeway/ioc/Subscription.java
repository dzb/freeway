package com.jujin.freeway.ioc;

import java.util.Objects;
import java.util.function.Consumer;

/** Handle for a runtime subscriber, returned by {@link EventBus#subscribe}. */
public final class Subscription<E> implements Consumer<E> {
    private final Class<E> eventType;
    private final Consumer<E> handler;

    Subscription(Class<E> eventType, Consumer<E> handler) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    Class<E> eventType() {
        return eventType;
    }

    @Override
    public void accept(E event) {
        handler.accept(event);
    }
}
