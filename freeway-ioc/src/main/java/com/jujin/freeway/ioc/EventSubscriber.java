package com.jujin.freeway.ioc;

import com.jujin.freeway.ioc.extension.Contribution;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class EventSubscriber<E> implements Contribution {
    private final Class<E> eventType;
    private final Consumer<E> handler;
    private final String id;
    private final String topic;
    private final List<String> beforeIds = new ArrayList<>();
    private final List<String> afterIds = new ArrayList<>();

    private EventSubscriber(Class<E> eventType, Consumer<E> handler, String id, String topic) {
        this.eventType = eventType;
        this.handler = Objects.requireNonNull(handler, "handler");
        this.id = id;
        this.topic = topic;
    }

    /** Class-based subscriber. */
    public static <E> EventSubscriber<E> of(Class<E> eventType, Consumer<E> handler) {
        return new EventSubscriber<>(Objects.requireNonNull(eventType, "eventType"), handler, null, null);
    }

    /** Class-based subscriber with id for ordering. */
    public static <E> EventSubscriber<E> of(String id, Class<E> eventType, Consumer<E> handler) {
        return new EventSubscriber<>(Objects.requireNonNull(eventType, "eventType"), handler, Objects.requireNonNull(id, "id"), null);
    }

    /** String-topic subscriber. */
    @SuppressWarnings("unchecked")
    public static EventSubscriber<Object> of(String topic, Consumer<Object> handler) {
        return new EventSubscriber<>(null, handler, null, Objects.requireNonNull(topic, "topic"));
    }

    /** String-topic subscriber with id for ordering. */
    @SuppressWarnings("unchecked")
    public static EventSubscriber<Object> of(String id, String topic, Consumer<Object> handler) {
        return new EventSubscriber<>(null, handler, Objects.requireNonNull(id, "id"), Objects.requireNonNull(topic, "topic"));
    }

    Class<E> eventType() { return eventType; }
    Consumer<E> handler() { return handler; }
    String id() { return id; }
    String topic() { return topic; }

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
