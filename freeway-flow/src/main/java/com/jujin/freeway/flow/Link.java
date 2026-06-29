package com.jujin.freeway.flow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连接
 *
 * @author noear
 * @since 3.0
 */
public class Link implements Comparable<Link> {
    private final Graph graph;

    private final String nextId;
    private final String title;
    private final Map<String, Object> metas;
    private final int priority;

    private final String prevId;
    private final ConditionDesc when;
    private Node prevNode, nextNode;

    public Link(Graph graph, String prevId, LinkSpec spec) {
        this.graph = graph;
        this.prevId = prevId;

        this.nextId = spec.getNextId();
        this.title = spec.getTitle();
        this.priority = spec.getPriority();
        this.when = new ConditionDesc(graph, spec.getWhen(), spec.getWhenComponent());

        if (spec.getMeta() == null) {
            this.metas = Collections.emptyMap();
        } else {
            this.metas = Collections.unmodifiableMap(new LinkedHashMap<>(spec.getMeta()));
        }
    }

    public Graph getGraph() { return graph; }
    public String getTitle() { return title; }
    public Map<String, Object> getMetas() { return metas; }
    public Object getMeta(String key) { return metas.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T getMetaAs(String key) { return (T) metas.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T getMetaOrDefault(String key, T def) {
        return (T) metas.getOrDefault(key, def);
    }

    public ConditionDesc getWhen() { return when; }
    public String getPrevId() { return prevId; }
    public String getNextId() { return nextId; }

    public Node getPrevNode() {
        if (prevNode == null) {
            prevNode = graph.getNode(getPrevId());
        }
        return prevNode;
    }

    public Node getNextNode() {
        if (nextNode == null) {
            nextNode = graph.getNode(getNextId());
        }
        return nextNode;
    }

    @Override
    public int compareTo(Link o) {
        return Integer.compare(o.priority, this.priority); // 大的在前
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        buf.append("priority=").append(priority);
        buf.append(", prevId='").append(getPrevId()).append('\'');
        buf.append(", nextId='").append(getNextId()).append('\'');
        if (title != null && !title.isEmpty()) {
            buf.append(", title='").append(title).append('\'');
        }
        if (!metas.isEmpty()) {
            buf.append(", meta=").append(metas);
        }
        if (when != null && !when.isEmpty()) {
            buf.append(", when=").append(when.getDescription());
        }
        if (when.getComponent() != null) {
            buf.append(", whenComponent=").append(when.getComponent());
        }
        buf.append("}");
        return buf.toString();
    }
}
