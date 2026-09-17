package com.jujin.freeway.flow;

import java.io.Serializable;

/**
 * Node record
 */
public class NodeRecord implements Serializable {
    private String graphId;
    private String id;
    private String title;
    private NodeType type;
    private long timestamp;

    public NodeRecord() {
        // for deserialization
    }

    public NodeRecord(Node node) {
        this.graphId = node.graph().id();
        this.id = node.id();
        this.title = node.title();
        this.type = node.type();
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isEnd() {
        return NodeType.END == type;
    }

    public String graphId() {
        return graphId;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public NodeType type() {
        return type;
    }

    public long timestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "NodeRecord{" +
                "graphId='" + graphId + '\'' +
                ", id='" + id + '\'' +
                ", title='" + title + '\'' +
                ", type=" + type +
                '}';
    }
}
