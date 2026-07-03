package com.jujin.freeway.flow.v2;

import com.jujin.freeway.flow.ConditionComponent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Link specification for v2 graph blueprints.
 *
 * <p>Links are created via {@link GraphSpec2#link(String, String)}.
 */
public final class LinkSpec2 {
    final String from;
    final String to;
    String title;
    final Map<String, Object> meta = new LinkedHashMap<>();
    String when;
    ConditionComponent whenComponent;
    int priority;

    LinkSpec2(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public LinkSpec2 title(String title) {
        this.title = title;
        return this;
    }

    public LinkSpec2 meta(Map<String, Object> meta) {
        if (meta != null && !meta.isEmpty()) {
            this.meta.putAll(meta);
        }
        return this;
    }

    public LinkSpec2 metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            this.meta.put(key, value);
        }
        return this;
    }

    public LinkSpec2 when(String when) {
        this.when = when;
        this.whenComponent = null;
        return this;
    }

    public LinkSpec2 when(ConditionComponent whenComponent) {
        this.whenComponent = whenComponent;
        this.when = null;
        return this;
    }

    public LinkSpec2 priority(int priority) {
        this.priority = priority;
        return this;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public String getTitle() {
        return title;
    }

    public Map<String, Object> getMeta() {
        return Collections.unmodifiableMap(meta);
    }

    public String getWhen() {
        return when;
    }

    public ConditionComponent getWhenComponent() {
        return whenComponent;
    }

    public int getPriority() {
        return priority;
    }

    @Override
    public String toString() {
        return from + " -> " + to + (title != null ? " (" + title + ")" : "");
    }
}
