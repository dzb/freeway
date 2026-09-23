package com.jujin.freeway.flow;

/**
 * Task description: the resolved reference a node executes. The v3 schema
 * knows three forms — an inline {@link TaskHandler}, a container reference
 * {@code @name}, and a sub-graph call {@code #graphId} (a node may also carry
 * only {@code data}, writing values without a task).
 */
public final class TaskDesc {
    private final Node node;
    private final String description;
    private final TaskHandler handler;

    public TaskDesc(Node node, String description) {
        this(node, description, null);
    }

    public TaskDesc(Node node, String description, TaskHandler handler) {
        this.node = node;
        this.description = description == null ? null : description.trim();
        this.handler = handler;
    }

    public Node node() { return node; }
    public String description() { return description; }
    public TaskHandler handler() { return handler; }

    public boolean isEmpty() {
        return (description == null || description.isEmpty()) && handler == null;
    }

    /** True for a {@code #graphId} sub-graph reference. */
    public boolean isGraphRef() {
        return description != null && description.startsWith("#") && description.length() > 1;
    }

    /** True for a {@code @name} container reference. */
    public boolean isHandlerRef() {
        return description != null && description.startsWith("@") && description.length() > 1;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "{nodeId='" + node.id() + "', description=null}";
        }
        return "{nodeId='" + node.id() + "', description='" + description + "'}";
    }
}
