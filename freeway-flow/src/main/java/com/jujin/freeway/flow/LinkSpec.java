package com.jujin.freeway.flow;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Link specification for a graph blueprint.
 *
 * <p>Links are created via {@link GraphSpec#link(String, String)}.
 */
public final class LinkSpec {
    private final GraphSpec owner;
    final String from;
    final String to;
    String title;
    final Map<String, Object> meta = new LinkedHashMap<>();
    String when;
    ConditionHandler whenHandler;
    int priority;

    LinkSpec(GraphSpec owner, String from, String to) {
        this.owner = owner;
        this.from = from;
        this.to = to;
    }

    private void touch() {
        if (owner != null) {
            owner.invalidate();
        }
    }

    public LinkSpec title(String title) {
        this.title = title;
        touch();
        return this;
    }

    public LinkSpec meta(Map<String, Object> meta) {
        if (meta != null && !meta.isEmpty()) {
            this.meta.putAll(meta);
        }
        touch();
        return this;
    }

    public LinkSpec metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            this.meta.put(key, value);
        }
        touch();
        return this;
    }

    public LinkSpec when(String when) {
        this.when = when;
        this.whenHandler = null;
        touch();
        return this;
    }

    public LinkSpec when(ConditionHandler whenHandler) {
        this.whenHandler = whenHandler;
        this.when = null;
        touch();
        return this;
    }

    public LinkSpec priority(int priority) {
        this.priority = priority;
        touch();
        return this;
    }

    public String from() {
        return from;
    }

    public String to() {
        return to;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> meta() {
        return Collections.unmodifiableMap(meta);
    }

    public String when() {
        return when;
    }

    public ConditionHandler whenHandler() {
        return whenHandler;
    }

    public int priority() {
        return priority;
    }

    @Override
    public String toString() {
        return from + " -> " + to + (title != null ? " (" + title + ")" : "");
    }
}