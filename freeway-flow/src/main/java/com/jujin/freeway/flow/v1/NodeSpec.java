package com.jujin.freeway.flow.v1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.jujin.freeway.flow.ConditionComponent;
import com.jujin.freeway.flow.NodeType;
import com.jujin.freeway.flow.TaskComponent;

/**
 * 节点定义
 *
 * @author noear
 * @since 3.0
 */
public class NodeSpec {
    private final String id;
    private String title;
    private NodeType type;
    private final Map<String, Object> meta = new LinkedHashMap<>();
    private final List<LinkSpec> links = new ArrayList<>();
    private String when;
    private ConditionComponent whenComponent;
    private String task;
    private TaskComponent taskComponent;

    public NodeSpec(String id, NodeType type) {
        this.id = id;
        this.type = type;
    }

    public NodeSpec then(Consumer<NodeSpec> consumer) {
        consumer.accept(this);
        return this;
    }

    public NodeSpec title(String title) {
        this.title = title;
        return this;
    }

    public NodeSpec meta(Map<String, Object> map) {
        if (map != null && !map.isEmpty()) {
            this.meta.putAll(map);
        }
        return this;
    }

    public NodeSpec metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            this.meta.put(key, value);
        }
        return this;
    }

    public NodeSpec linkAdd(String nextId, Consumer<LinkSpec> configure) {
        LinkSpec linkSpec = new LinkSpec(nextId);
        if (configure != null) {
            configure.accept(linkSpec);
        }
        this.links.add(linkSpec);
        return this;
    }

    public NodeSpec linkAdd(String nextId) {
        return linkAdd(nextId, null);
    }

    public NodeSpec linkRemove(String nextId) {
        this.links.removeIf(l -> l.getNextId().equals(nextId));
        return this;
    }

    public NodeSpec linkClear() {
        this.links.clear();
        return this;
    }

    public NodeSpec when(String when) {
        this.when = when;
        return this;
    }

    public NodeSpec when(ConditionComponent whenComponent) {
        this.whenComponent = whenComponent;
        return this;
    }

    public NodeSpec task(String task) {
        this.task = task;
        return this;
    }

    public NodeSpec task(TaskComponent taskComponent) {
        this.taskComponent = taskComponent;
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder("{");
        buf.append("id='").append(id).append('\'');
        if (title != null && !title.isEmpty()) {
            buf.append(", title='").append(title).append('\'');
        }
        if (type != null) {
            buf.append(", type=").append(type);
        }
        if (!meta.isEmpty()) {
            buf.append(", meta=").append(meta);
        }
        if (!links.isEmpty()) {
            buf.append(", links=").append(links);
        }
        if (when != null && !when.isEmpty()) {
            buf.append(", when='").append(when).append('\'');
        }
        if (whenComponent != null) {
            buf.append(", whenComponent=").append(whenComponent);
        }
        if (task != null && !task.isEmpty()) {
            buf.append(", task='").append(task).append('\'');
        }
        if (taskComponent != null) {
            buf.append(", taskComponent=").append(taskComponent);
        }
        buf.append('}');
        return buf.toString();
    }

    // --- static factories ---
    public static NodeSpec startOf(String id) { return new NodeSpec(id, NodeType.START); }
    public static NodeSpec endOf(String id) { return new NodeSpec(id, NodeType.END); }
    public static NodeSpec activityOf(String id) { return new NodeSpec(id, NodeType.ACTIVITY); }
    public static NodeSpec inclusiveOf(String id) { return new NodeSpec(id, NodeType.INCLUSIVE); }
    public static NodeSpec exclusiveOf(String id) { return new NodeSpec(id, NodeType.EXCLUSIVE); }
    public static NodeSpec parallelOf(String id) { return new NodeSpec(id, NodeType.PARALLEL); }
    public static NodeSpec loopOf(String id) { return new NodeSpec(id, NodeType.LOOP); }

    // --- getters ---
    public String getId() { return id; }
    public String getTitle() { return title; }
    public NodeType getType() { return type; }
    public Map<String, Object> getMeta() { return meta; }
    public List<LinkSpec> getLinks() { return links; }
    public String getWhen() { return when; }
    public ConditionComponent getWhenComponent() { return whenComponent; }
    public String getTask() { return task; }
    public TaskComponent getTaskComponent() { return taskComponent; }
}
