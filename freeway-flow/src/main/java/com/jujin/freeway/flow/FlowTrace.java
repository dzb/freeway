package com.jujin.freeway.flow;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Flow trace (lightweight tracking, supports persistence-based resume)
 *
 * @author noear
 * @since 3.8.1
 */
public class FlowTrace implements Serializable {
    private volatile boolean enabled = true;
    private volatile String rootGraphId;
    private final Map<String, NodeRecord> lastRecords = new ConcurrentHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void enable(boolean enabled) { this.enabled = enabled; }

    public String getRootGraphId() { return rootGraphId; }
    public void setRootGraphId(String rootGraphId) { this.rootGraphId = rootGraphId; }

    public Collection<NodeRecord> lastRecords() { return lastRecords.values(); }

    public void clear() {
        rootGraphId = null;
        lastRecords.clear();
    }

    /**
     * Restores a per-graph record during deserialization. {@code lastRecords}
     * is a final field that bean-coercion cannot populate, so JSON restore
     * must feed it explicitly — without this, a paused run's resume position
     * silently resets to the graph start after a round-trip.
     */
    public void restoreRecord(String graphId, NodeRecord record) {
        if (record != null) {
            lastRecords.put(graphId, record);
        }
    }

    public void recordNodeId(Graph graph, String nodeId) {
        if (!enabled) return;
        Objects.requireNonNull(graph, "graph");
        if (nodeId == null) {
            lastRecords.remove(graph.getId());
        } else {
            recordNode(graph, graph.getNodeOrThrow(nodeId));
        }
    }

    public void recordNode(Graph graph, Node node) {
        if (!enabled) return;
        Objects.requireNonNull(graph, "graph");
        if (rootGraphId == null) {
            rootGraphId = graph.getId();
        }
        if (node == null) {
            lastRecords.remove(graph.getId());
        } else {
            lastRecords.put(graph.getId(), new NodeRecord(node));
        }
    }

    public NodeRecord lastRecord(String graphId) {
        if (!enabled) return null;
        if (graphId == null) graphId = rootGraphId;
        if (graphId == null) return null;
        return lastRecords.get(graphId);
    }

    public Node lastNode(Graph graph) {
        NodeRecord tmp = lastRecord(graph.getId());
        if (tmp == null) return graph.getStart();
        return graph.getNodeOrThrow(tmp.getId());
    }

    public String lastNodeId(String graphId) {
        NodeRecord tmp = lastRecord(graphId);
        return tmp != null ? tmp.getId() : null;
    }

    public boolean isEnd(String graphId) {
        NodeRecord tmp = lastRecord(graphId);
        if (tmp == null) return false;
        return tmp.isEnd();
    }
}
