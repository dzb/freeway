package com.jujin.freeway.flow;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 任务描述（表达式参考：'@beanName' / '#graphId' / '$metaKey' / '!markerName'）
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

    /**
     * Returns true if this task description uses marker-based resolution
     * (starts with {@code !}).
     */
    public boolean isMarkerRef() {
        return description != null && description.startsWith("!");
    }

    /**
     * Returns the set of marker names from the description.
     * Markers are space-separated and each starts with {@code !}.
     * Example: {@code "!channel:notification !priority:high"} →
     * {@code {"channel:notification", "priority:high"}}
     */
    public Set<String> getMarkerNames() {
        if (!isMarkerRef()) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (String part : description.split("\\s+")) {
            if (part.startsWith("!") && part.length() > 1) {
                names.add(part.substring(1));
            }
        }
        return Collections.unmodifiableSet(names);
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
