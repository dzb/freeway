package com.jujin.freeway.flow;

import java.io.Serializable;

/**
 * 节点记录
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
        // 用于反序列化
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

    public String getGraphId() {
        return graphId;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public NodeType getType() {
        return type;
    }

    public long getTimestamp() {
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
