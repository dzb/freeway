package com.jujin.freeway.flow;

/**
 * Condition description (typically used for branch conditions)
 */
public final class ConditionDesc {
    private final Ref<Graph, ConditionHandler> ref;

    public ConditionDesc(Graph graph, String description) {
        this(graph, description, null);
    }

    public ConditionDesc(Graph graph, String description, ConditionHandler handler) {
        this.ref = new Ref<>(graph, description, handler);
    }

    public Graph graph() { return ref.owner(); }

    public String description() { return ref.description(); }

    public ConditionHandler handler() { return ref.handler(); }

    public boolean isEmpty() {
        return ref.isEmpty();
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "{description=null}";
        }
        return "{description='" + description() + "'}";
    }
}
