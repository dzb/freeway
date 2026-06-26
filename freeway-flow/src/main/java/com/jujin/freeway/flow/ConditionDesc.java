package com.jujin.freeway.flow;

/**
 * 条件描述（一般用于分支条件）
 *
 * @author noear
 * @since 3.0
 */
public class ConditionDesc {
    public static boolean isNotEmpty(ConditionDesc c) {
        return c != null && !c.isEmpty();
    }

    private final Graph graph;
    private final String description;
    private final ConditionComponent component;
    public Object attachment;

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
