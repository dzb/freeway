package com.jujin.freeway.flow;

/**
 * 任务描述（表达式参考：'@beanName' / '#graphId' / '$metaKey'）
 *
 * @author noear
 * @since 3.0
 */
public class TaskDesc {
    public static boolean isNotEmpty(TaskDesc t) {
        return t != null && !t.isEmpty();
    }

    private final Node node;
    private final String description;
    private final TaskComponent component;
    public Object attachment;

    public TaskDesc(Node node, String description) {
        this.node = node;
        this.description = (description != null) ? description.trim() : null;
        this.component = null;
    }

    public TaskDesc(Node node, String description, TaskComponent component) {
        this.node = node;
        this.description = (description != null) ? description.trim() : null;
        this.component = component;
    }

    public Node getNode() { return node; }
    public String getDescription() { return description; }
    public TaskComponent getComponent() { return component; }

    public boolean isEmpty() {
        return (description == null || description.isEmpty()) && component == null;
    }

    @Override
    public String toString() {
        if (isEmpty()) {
            return "{nodeId='" + node.getId() + "', description=null}";
        } else {
            return "{nodeId='" + node.getId() + "', description='" + description + "'}";
        }
    }
}
