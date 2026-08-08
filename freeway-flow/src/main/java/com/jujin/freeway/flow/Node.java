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
    private volatile List<Node> prevNodes, nextNodes;
    private volatile List<Link> prevLinks;
    public Object attachment;

    public Node(Graph graph, NodeSpec spec, List<Link> links) {
        this(graph, spec, spec.getType(), links);
    }

    public Node(Graph graph, NodeSpec spec, NodeType type, List<Link> links) {
        this.graph = graph;
        this.id = spec.getId();
        this.title = spec.getTitle();
        this.type = type;
        this.when = new ConditionDesc(graph, spec.getWhen(), spec.getWhenComponent());
        this.task = new TaskDesc(this, spec.getTask(), spec.getTaskComponent());

        if (spec.getMeta() == null || spec.getMeta().isEmpty()) {
            this.metas = Collections.emptyMap();
        } else {
            this.metas = Collections.unmodifiableMap(new LinkedHashMap<>(spec.getMeta()));
        }

        if (links == null || links.isEmpty()) {
            this.nextLinks = Collections.emptyList();
        } else {
            Collections.sort(links);
            this.nextLinks = Collections.unmodifiableList(new ArrayList<>(links));
        }
    }

    public Graph getGraph() { return graph; }
    public String getId() { return id; }
    public String getTitle() { return title; }
    public NodeType getType() { return type; }
    public Map<String, Object> getMetas() { return metas; }

    public Object getMeta(String key) { return metas.get(key); }

    @SuppressWarnings("unchecked")
    public <T> T getMetaAs(String key) { return (T) metas.get(key); }

    public boolean hasMeta(String key) { return metas.containsKey(key); }

    public String getMetaAsString(String key) {
        Object tmp = metas.get(key);
        if (tmp == null) return null;
        if (tmp instanceof String) return (String) tmp;
        return tmp.toString();
    }

    public Boolean getMetaAsBool(String key) {
        Object tmp = metas.get(key);
        if (tmp == null) return null;
        if (tmp instanceof Boolean) return (Boolean) tmp;
        if (tmp instanceof String) return Boolean.parseBoolean((String) tmp);
        if (tmp instanceof Number) return ((Number) tmp).doubleValue() > 0;
        throw new UnsupportedOperationException(key);
    }

    public Number getMetaAsNumber(String key) {
        Object tmp = metas.get(key);
        if (tmp == null) return null;
        if (tmp instanceof String) return Double.parseDouble((String) tmp);
        if (tmp instanceof Number) return (Number) tmp;
        throw new UnsupportedOperationException(key);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetaOrDefault(String key, T def) {
        return (T) metas.getOrDefault(key, def);
    }

    public List<Link> getPrevLinks() {
        if (prevLinks == null) {
            List<Link> tmp = new ArrayList<>();
            if (getType() != NodeType.START) {
                for (Link l : graph.getLinks()) {
                    if (getId().equals(l.getNextId())) {
                        tmp.add(l);
                    }
                }
                Collections.reverse(tmp);
            }
            prevLinks = Collections.unmodifiableList(tmp);
        }
        return prevLinks;
    }

    public List<Link> getNextLinks() { return nextLinks; }

    public List<Node> getPrevNodes() {
        if (prevNodes == null) {
            List<Node> tmp = new ArrayList<>();
            if (getType() != NodeType.START) {
                for (Link l : graph.getLinks()) {
                    if (getId().equals(l.getNextId())) {
                        tmp.add(graph.getNode(l.getPrevId()));
                    }
                }
            }
            prevNodes = Collections.unmodifiableList(tmp);
        }
        return prevNodes;
    }

    public List<Node> getNextNodes() {
        if (nextNodes == null) {
            List<Node> tmp = new ArrayList<>();
            if (getType() != NodeType.END) {
                for (Link l : this.getNextLinks()) {
                    tmp.add(graph.getNode(l.getNextId()));
                }
            }
            nextNodes = Collections.unmodifiableList(tmp);
        }
        return nextNodes;
    }

    public Node getNextNode() {
        if (getNextNodes().size() > 0) {
            return getNextNodes().get(0);
        }
        return null;
    }

    public ConditionDesc getWhen() { return when; }
    public TaskDesc getTask() { return task; }

    @Override
    public int hashCode() {
        return Objects.hash(id, graph.getId());
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Node other) {
            return other.getId().equals(getId())
                    && other.getGraph().getId().equals(getGraph().getId());
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
            buf.append(", when='").append(when.getDescription()).append('\'');
        }
        if (when.getComponent() != null) {
            buf.append(", whenComponent=").append(when.getComponent());
        }
        if (task != null && !task.isEmpty()) {
            buf.append(", task='").append(task.getDescription()).append('\'');
        }
        if (task.getComponent() != null) {
            buf.append(", taskComponent=").append(task.getComponent());
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
