package com.jujin.freeway.flow;


import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Node specification for a graph blueprint.
 *
 * <p>Nodes are created via {@link GraphSpec#addNode(String, NodeType)}
 * or convenience methods like {@link GraphSpec#addStart(String)}.
 * {@link #linkAdd(String)} and {@link #linkAdd(String, Consumer)}
 * store pending links that are collected and instantiated by
 * {@link GraphSpec} during {@code create()}.
 */
public final class NodeSpec {
    private final GraphSpec owner;
    final String id;
    final NodeType type;
    String title;
    final Map<String, Object> meta = new LinkedHashMap<>();
    final List<PendingLink> pendingLinks = new ArrayList<>();
    String when;
    ConditionComponent whenComponent;
    String task;
    TaskComponent taskComponent;

    NodeSpec(GraphSpec owner, String id, NodeType type) {
        this.owner = owner;
        this.id = id;
        this.type = type == null ? NodeType.ACTIVITY : type;
    }

    private void touch() {
        if (owner != null) {
            owner.invalidate();
        }
    }

    public NodeSpec title(String title) {
        this.title = title;
        touch();
        return this;
    }

    public NodeSpec meta(Map<String, Object> meta) {
        if (meta != null && !meta.isEmpty()) {
            this.meta.putAll(meta);
        }
        touch();
        return this;
    }

    public NodeSpec metaPut(String key, Object value) {
        if (key != null && !key.isEmpty()) {
            this.meta.put(key, value);
        }
        touch();
        return this;
    }

    public NodeSpec when(String when) {
        this.when = when;
        this.whenComponent = null;
        touch();
        return this;
    }

    public NodeSpec when(ConditionComponent whenComponent) {
        this.whenComponent = whenComponent;
        this.when = null;
        touch();
        return this;
    }

    public NodeSpec task(String task) {
        this.task = task;
        this.taskComponent = null;
        touch();
        return this;
    }

    public NodeSpec task(TaskComponent taskComponent) {
        this.taskComponent = taskComponent;
        this.task = null;
        touch();
        return this;
    }

    /**
     * Stores a pending link from this node to {@code to}.
     * The link is instantiated later by {@link GraphSpec#create()}.
     */
    public NodeSpec linkAdd(String to) {
        pendingLinks.add(new PendingLink(
            Objects.requireNonNull(to, "to must not be null"), null));
        touch();
        return this;
    }

    public NodeSpec linkAdd(String to, Consumer<LinkSpec> configure) {
        pendingLinks.add(new PendingLink(
            Objects.requireNonNull(to, "to must not be null"), configure));
        touch();
        return this;
    }

    /**
     * Drains and returns the pending links for this node, clearing the internal list.
     * Called by {@link GraphSpec} during {@code create()}.
     */
    List<PendingLink> drainPendingLinks() {
        if (pendingLinks.isEmpty()) return List.of();
        List<PendingLink> result = List.copyOf(pendingLinks);
        pendingLinks.clear();
        return result;
    }

    /**
     * A pending link from this node to a target, with optional configuration.
     * Instantiated as {@link LinkSpec} by GraphSpec during create().
     */
    record PendingLink(String to, Consumer<LinkSpec> configure) {}

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