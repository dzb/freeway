package com.jujin.freeway.flow;

/**
 * Condition description (typically used for branch conditions)
 */
public final class ConditionDesc {
    private final Graph graph;
    private final String description;
    private final ConditionComponent component;

    public ConditionDesc(Graph graph, String description) {
        this.graph = graph;
        this.description = (description != null) ? description.trim() : null;
        this.component = null;
    }

    public ConditionDesc(Graph graph, String description, ConditionComponent component) {
        this.graph = graph;
        this.description = (description != null) ? description.trim() : null;
        this.component = component;
    }

    public Graph graph() { return graph; }
    public String description() { return description; }
    public ConditionComponent component() { return component; }

    public boolean isEmpty() {
        return (description == null || description.isEmpty()) && component == null;
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
