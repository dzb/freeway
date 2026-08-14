package com.jujin.freeway.flow;

/**
 * Condition description (typically used for branch conditions)
 *
 * @author noear
 * @since 3.0
 */
public class ConditionDesc {
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

    public Graph getGraph() { return graph; }
    public String getDescription() { return description; }
    public ConditionComponent getComponent() { return component; }

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
