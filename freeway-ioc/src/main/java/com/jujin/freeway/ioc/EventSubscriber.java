package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contribution;
import java.util.Objects;
import java.util.function.Consumer;

public final class EventSubscriber<E> implements Contribution {
    private final Class<E> eventType;
    private final Consumer<E> handler;
    private final String id;
    private final java.util.List<String> beforeIds = new java.util.ArrayList<>();
    private final java.util.List<String> afterIds = new java.util.ArrayList<>();

    private EventSubscriber(Class<E> eventType, Consumer<E> handler, String id) {
        this.eventType = Objects.requireNonNull(eventType, "eventType");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.id = id;
    }

    public static <E> EventSubscriber<E> of(Class<E> eventType, Consumer<E> handler) {
        return new EventSubscriber<>(eventType, handler, null);
    }

    public static <E> EventSubscriber<E> of(String id, Class<E> eventType, Consumer<E> handler) {
        return new EventSubscriber<>(eventType, handler, Objects.requireNonNull(id, "id"));
    }

    Class<E> eventType() { return eventType; }
    Consumer<E> handler() { return handler; }
    String id() { return id; }

    @Override
    public Contribution before(String... ids) {
        for (String s : ids) beforeIds.add(s);
        return this;
    }

    @Override
    public Contribution after(String... ids) {
        for (String s : ids) afterIds.add(s);
        return this;
    }
}
