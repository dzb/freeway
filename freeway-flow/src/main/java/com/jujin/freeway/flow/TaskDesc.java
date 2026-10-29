package com.jujin.freeway.flow;

/**
 * Task description: the resolved reference a node executes. The v3 schema
 * knows three forms — an inline {@link TaskHandler}, a container reference
 * {@code @name}, and a sub-graph call {@code #graphId} (a node may also carry
 * only {@code data}, writing values without a task).
 */
public final class TaskDesc {
    private final Ref<Node, TaskHandler> ref;

    public TaskDesc(Node node, String description) {
        this(node, description, null);
    }

    public TaskDesc(Node node, String description, TaskHandler handler) {
        this.ref = new Ref<>(node, description, handler);
    }

    public Node node() { return ref.owner(); }

    public String description() { return ref.description(); }

    public TaskHandler handler() { return ref.handler(); }

    public boolean isEmpty() {
        return ref.isEmpty();
    }

    /** True for a {@code #graphId} sub-graph reference. */
    public boolean isGraphRef() {
        String description = ref.description();
        return description != null && description.startsWith("#") && description.length() > 1;
    }

    /** True for a {@code @name} container reference. */
    public boolean isHandlerRef() {
        String description = ref.description();
        return description != null && description.startsWith("@") && description.length() > 1;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "{nodeId='" + node().id() + "', description=null}";
        }
        return "{nodeId='" + node().id() + "', description='" + description() + "'}";
    }
}
