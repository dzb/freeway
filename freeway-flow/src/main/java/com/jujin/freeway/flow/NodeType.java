package com.jujin.freeway.flow;

/**
 * 节点类型
 *
 * @author noear
 * @since 3.0
 */
public enum NodeType {
    UNKNOWN(0),
    START(1),
    END(2),
    ACTIVITY(11),
    EXCLUSIVE(21),
    INCLUSIVE(31),
    PARALLEL(32),
    LOOP(33);

    private final int code;

    NodeType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 根据名称获取类型（不区分大小写）
     */
    public static NodeType nameOf(String name) {
        if (name == null || name.isEmpty()) {
            return ACTIVITY; // 默认为活动节点
        }

        for (NodeType v : values()) {
            if (v.name().equalsIgnoreCase(name)) {
                return v;
            }
        }

        return ACTIVITY;
    }
}
