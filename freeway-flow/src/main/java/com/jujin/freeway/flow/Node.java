package com.jujin.freeway.flow;


import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Node
 *
 * @author noear
 * @since 3.0
 */
public class Node {
    public static final String TAG = "node";

    private final Graph graph;
    private final String id;
    private final String title;
    private final NodeType type;
    private final Map<String, Object> metas;
    private final ConditionDesc when;
    private final TaskDesc task;
    private final List<Link> nextLinks;

    // Lazily computed graph topology views. Volatile: a shared graph may be
    // executed by multiple threads (PARALLEL branches), so first access must
    // publish the computed list across threads instead of silently
    // recomputing per thread.
    private volatile List<Node> nextNodes;
    private volatile List<Node> prevNodes;
    private volatile List<Link> prevLinks;

    /** Arbitrary per-node attachment for application use. */
    public Object attachment;

    public Node(Graph graph, NodeSpec spec, List<Link> links) {
        this(graph, spec, spec.type(), links);
    }

    public Node(Graph graph, NodeSpec spec, NodeType type, List<Link> links) {
        this.graph = graph;
        this.id = spec.id();
        this.title = spec.title();
        this.type = type;
        this.when = new ConditionDesc(graph, spec.when(), spec.whenComponent());
        this.task = new TaskDesc(this, spec.task(), spec.taskComponent());

        if (spec.meta() == null || spec.meta().isEmpty()) {
            this.metas = Map.of();
        } else {
            this.metas = Collections.unmodifiableMap(new LinkedHashMap<>(spec.meta()));
        }

        if (links == null || links.isEmpty()) {
            this.nextLinks = List.of();
        } else {
            // Sort a copy: the caller's list must not be reordered as a side
            // effect of building this node.
            List<Link> ordered = new ArrayList<>(links);
            Collections.sort(ordered);
            this.nextLinks = List.copyOf(ordered);
        }
    }

    public Graph graph() { return graph; }
    public String id() { return id; }
    public String title() { return title; }
    public NodeType type() { return type; }
    public Map<String, Object> metas() { return metas; }

    public Object meta(String key) { return metas.get(key); }

    public String metaAsString(String key) {
        Object tmp = metas.get(key);
        if (tmp == null) return null;
        if (tmp instanceof String) return (String) tmp;
        return tmp.toString();
    }

    /** Returns the meta value cast to the requested type. */
    @SuppressWarnings("unchecked")
    public <T> T metaAs(String key) {
        return (T) metas.get(key);
    }

    /** Returns the meta value cast to the requested type, or {@code def}. */
    @SuppressWarnings("unchecked")
    public <T> T metaOrDefault(String key, T def) {
        return (T) metas.getOrDefault(key, def);
    }

    public boolean hasMeta(String key) {
        return metas.containsKey(key);
    }

    public Boolean metaAsBool(String key) {
        Object tmp = metas.get(key);
        if (tmp == null) return null;
        if (tmp instanceof Boolean) return (Boolean) tmp;
        if (tmp instanceof String) return Boolean.parseBoolean((String) tmp);
        if (tmp instanceof Number) return ((Number) tmp).doubleValue() > 0;
        throw new UnsupportedOperationException(
            "Cannot read meta '" + key + "' as boolean: " + tmp.getClass().getName());
    }

    public Number metaAsNumber(String key) {
        Object tmp = metas.get(key);
        if (tmp == null) return null;
        if (tmp instanceof String) return Double.parseDouble((String) tmp);
        if (tmp instanceof Number) return (Number) tmp;
        throw new UnsupportedOperationException(
            "Cannot read meta '" + key + "' as number: " + tmp.getClass().getName());
    }

    public List<Link> prevLinks() {
        if (prevLinks == null) {
            List<Link> tmp = new ArrayList<>();
            if (type() != NodeType.START) {
                for (Link l : graph.links()) {
                    if (id().equals(l.nextId())) {
                        tmp.add(l);
                    }
                }
                Collections.reverse(tmp);
            }
            prevLinks = List.copyOf(tmp);
        }
        return prevLinks;
    }

    public List<Link> nextLinks() { return nextLinks; }

    public List<Node> nextNodes() {
        if (nextNodes == null) {
            List<Node> tmp = new ArrayList<>();
            if (type() != NodeType.END) {
                for (Link l : this.nextLinks()) {
                    tmp.add(graph.node(l.nextId()));
                }
            }
            nextNodes = List.copyOf(tmp);
        }
        return nextNodes;
    }

    public Node nextNode() {
        if (nextNodes().size() > 0) {
            return nextNodes().get(0);
        }
        return null;
    }

    public List<Node> prevNodes() {
        if (prevNodes == null) {
            List<Node> tmp = new ArrayList<>();
            if (type() != NodeType.START) {
                for (Link l : graph.links()) {
                    if (id().equals(l.nextId())) {
                        tmp.add(graph.node(l.prevId()));
                    }
                }
            }
            prevNodes = List.copyOf(tmp);
        }
        return prevNodes;
    }

    public ConditionDesc when() { return when; }
    public TaskDesc task() { return task; }

    @Override
    public int hashCode() {
        return Objects.hash(id, graph.id());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Node other) {
            return other.id().equals(id())
                    && other.graph().id().equals(graph().id());
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        buf.append("id='").append(id).append('\'');
        buf.append(", type='").append(type).append('\'');
        if (title != null && !title.isEmpty()) {
            buf.append(", title='").append(title).append('\'');
        }
        if (when != null && !when.isEmpty()) {
            buf.append(", when='").append(when.description()).append('\'');
        }
        if (when.component() != null) {
            buf.append(", whenComponent=").append(when.component());
        }
        if (task != null && !task.isEmpty()) {
            buf.append(", task='").append(task.description()).append('\'');
        }
        if (task.component() != null) {
            buf.append(", taskComponent=").append(task.component());
        }
        if (!nextLinks.isEmpty()) {
            buf.append(", link=").append(nextLinks);
        }
        if (!metas.isEmpty()) {
            buf.append(", meta=").append(metas);
        }
        buf.append("}");
        return buf.toString();
    }
}
