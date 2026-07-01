package com.jujin.freeway.flow.v1;

import java.util.LinkedHashMap;
import java.util.Map;

import com.jujin.freeway.flow.ConditionComponent;

/**
 * 连接定义
 *
 * @author noear
 * @since 3.0
 */
public class LinkSpec {
    private final String nextId;
    private String title;
    private Map<String, Object> meta;
    private String when;
    private ConditionComponent whenComponent;
    private int priority;

    public LinkSpec(String nextId) {
        this.nextId = nextId;
    }

    public LinkSpec title(String title) {
        this.title = title;
        return this;
    }

    public LinkSpec meta(Map<String, Object> meta) {
        this.meta = meta;
        return this;
    }

    public LinkSpec metaPut(String key, Object value) {
        if (meta == null) {
            meta = new LinkedHashMap<>();
        }
        meta.put(key, value);
        return this;
    }

    public LinkSpec when(String condition) {
        this.when = condition;
        return this;
    }

    public LinkSpec when(ConditionComponent conditionComponent) {
        this.whenComponent = conditionComponent;
        return this;
    }

    public LinkSpec priority(int priority) {
        this.priority = priority;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        buf.append("nextId='").append(nextId).append('\'');
        if (title != null && !title.isEmpty()) {
            buf.append(", title='").append(title).append('\'');
        }
        if (when != null && !when.isEmpty()) {
            buf.append(", when='").append(when).append('\'');
        }
        if (whenComponent != null) {
            buf.append(", whenComponent=").append(whenComponent);
        }
        if (meta != null && !meta.isEmpty()) {
            buf.append(", meta=").append(meta);
        }
        buf.append("}");
        return buf.toString();
    }

    public String getNextId() { return nextId; }
    public String getTitle() { return title; }
    public Map<String, Object> getMeta() { return meta; }
    public String getWhen() { return when; }
    public ConditionComponent getWhenComponent() { return whenComponent; }
    public int getPriority() { return priority; }
}
