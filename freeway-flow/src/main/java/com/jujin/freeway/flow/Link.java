package com.jujin.freeway.flow;


import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Link
 */
public class Link implements Comparable<Link> {
    private final Graph graph;

    private final String nextId;
    private final String title;
    private final Map<String, Object> metas;
    private final int priority;

    private final String prevId;
    private final ConditionDesc when;
    // volatile: PARALLEL branches resolve these lazily from multiple threads —
    // same contract as Node's cached links (Node.java).
    private volatile Node prevNode;
    private volatile Node nextNode;

    public Link(Graph graph, String prevId, LinkSpec spec) {
        this.graph = graph;
        this.prevId = prevId;

        this.nextId = spec.to();
        this.title = spec.title();
        this.priority = spec.priority();
        this.when = new ConditionDesc(graph, spec.when(), spec.whenComponent());

        if (spec.meta() == null) {
            this.metas = Collections.emptyMap();
        } else {
            this.metas = Collections.unmodifiableMap(new LinkedHashMap<>(spec.meta()));
        }
    }

    public Graph graph() { return graph; }
    public String title() { return title; }
    public Map<String, Object> metas() { return metas; }
    public Object meta(String key) { return metas.get(key); }

    /** Returns the meta value cast to the requested type. */
    @SuppressWarnings("unchecked")
    public <T> T metaAs(String key) { return (T) metas.get(key); }

    /** Returns the meta value cast to the requested type, or {@code def}. */
    @SuppressWarnings("unchecked")
    public <T> T metaOrDefault(String key, T def) {
        return (T) metas.getOrDefault(key, def);
    }

    public ConditionDesc when() { return when; }
    public String prevId() { return prevId; }
    public String nextId() { return nextId; }
    public int priority() { return priority; }

    public Node nextNode() {
        if (nextNode == null) {
            nextNode = graph.node(nextId());
        }
        return nextNode;
    }

    public Node prevNode() {
        if (prevNode == null) {
            prevNode = graph.node(prevId());
        }
        return prevNode;
    }

    @Override
    public int compareTo(Link o) {
        return Integer.compare(o.priority, this.priority); // larger first
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        buf.append("priority=").append(priority);
        buf.append(", prevId='").append(prevId()).append('\'');
        buf.append(", nextId='").append(nextId()).append('\'');
        if (title != null && !title.isEmpty()) {
            buf.append(", title='").append(title).append('\'');
        }
        if (!metas.isEmpty()) {
            buf.append(", meta=").append(metas);
        }
        if (when != null && !when.isEmpty()) {
            buf.append(", when=").append(when.description());
        }
        if (when.component() != null) {
            buf.append(", whenComponent=").append(when.component());
        }
        buf.append("}");
        return buf.toString();
    }
}
