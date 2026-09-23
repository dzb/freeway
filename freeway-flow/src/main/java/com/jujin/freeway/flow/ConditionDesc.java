package com.jujin.freeway.flow;

/**
 * Condition description (typically used for branch conditions)
 */
public final class ConditionDesc {
    private final Graph graph;
    private final String description;
    private final ConditionHandler handler;

    public ConditionDesc(Graph graph, String description) {
        this.graph = graph;
        this.description = (description != null) ? description.trim() : null;
        this.handler = null;
    }

    public ConditionDesc(Graph graph, String description, ConditionHandler handler) {
        this.graph = graph;
        this.description = (description != null) ? description.trim() : null;
        this.handler = handler;
    }

    public Graph graph() { return graph; }
    public String description() { return description; }
    public ConditionHandler handler() { return handler; }

    public boolean isEmpty() {
        return (description == null || description.isEmpty()) && handler == null;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "{description=null}";
        } else {
            return "{description='" + description + "'}";
        }
    }
}
