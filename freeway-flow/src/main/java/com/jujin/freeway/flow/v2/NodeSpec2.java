package com.jujin.freeway.flow.v2;

import com.jujin.freeway.flow.ConditionComponent;
import com.jujin.freeway.flow.NodeType;
import com.jujin.freeway.flow.TaskComponent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Node specification for v2 graph blueprints.
 *
 * <p>Nodes are created via {@link GraphSpec2#addNode(String, NodeType)}
 * or convenience methods like {@link GraphSpec2#addStart(String)}.
 * {@link #linkAdd(String)} and {@link #linkAdd(String, Consumer)}
 * store pending links that are collected and instantiated by
 * {@link GraphSpec2} during {@code create()}.
 */
public final class NodeSpec2 {
    final String id;
    final NodeType type;
    String title;
    final Map<String, Object> meta = new LinkedHashMap<>();
    final List<PendingLink> pendingLinks = new ArrayList<>();
    String when;
    ConditionComponent whenComponent;
    String task;
    TaskComponent taskComponent;

    NodeSpec2(String id, NodeType type) {
        this.id = id;
        this.type = type == null ? NodeType.ACTIVITY : type;
    }

    public NodeSpec2 title(String title) {
        this.title = title;
        return this;
    }

    public NodeSpec2 meta(Map<String, Object> meta) {
        if (meta != null && !meta.isEmpty()) {
            this.meta.putAll(meta);
        }
        return this;
    }

    public NodeSpec2 metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            this.meta.put(key, value);
        }
        return this;
    }

    public NodeSpec2 when(String when) {
        this.when = when;
        this.whenComponent = null;
        return this;
    }

    public NodeSpec2 when(ConditionComponent whenComponent) {
        this.whenComponent = whenComponent;
        this.when = null;
        return this;
    }

    public NodeSpec2 task(String task) {
        this.task = task;
        this.taskComponent = null;
        return this;
    }

    public NodeSpec2 task(TaskComponent taskComponent) {
        this.taskComponent = taskComponent;
        this.task = null;
        return this;
    }

    /**
     * Stores a pending link from this node to {@code to}.
     * The link is instantiated later by {@link GraphSpec2#create()}.
     */
    public NodeSpec2 linkAdd(String to) {
        pendingLinks.add(new PendingLink(to, null));
        return this;
    }

    /**
     * Stores a pending link from this node to {@code to} with configuration.
     * The link is instantiated later by {@link GraphSpec2#create()}.
     */
    public NodeSpec2 linkAdd(String to, Consumer<LinkSpec2> configure) {
        pendingLinks.add(new PendingLink(to, configure));
        return this;
    }

    /**
     * Drains and returns the pending links for this node, clearing the internal list.
     * Called by {@link GraphSpec2} during {@code create()}.
     */
    List<PendingLink> drainPendingLinks() {
        if (pendingLinks.isEmpty()) return List.of();
        List<PendingLink> result = List.copyOf(pendingLinks);
        pendingLinks.clear();
        return result;
    }

    /**
     * A pending link from this node to a target, with optional configuration.
     * Instantiated as {@link LinkSpec2} by GraphSpec2 during create().
     */
    record PendingLink(String to, Consumer<LinkSpec2> configure) {}

    public String getId() {
        return id;
    }

    public NodeType getType() {
        return type;
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

    public String getTask() {
        return task;
    }

    public TaskComponent getTaskComponent() {
        return taskComponent;
    }
}
