package com.jujin.freeway.flow;

import java.io.Serializable;

/**
 * Node record
 *
 * @author noear
 * @since 3.8.1
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
        this.graphId = node.getGraph().getId();
        this.id = node.getId();
        this.title = node.getTitle();
        this.type = node.getType();
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isEnd() {
        return NodeType.END == type;
    }

    public String getId() {
        return id;
    }

    public NodeType getType() {
        return type;
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
