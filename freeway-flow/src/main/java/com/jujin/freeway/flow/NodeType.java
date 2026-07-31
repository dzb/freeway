package com.jujin.freeway.flow;

/**
 * 节点类型。
 *
 * <p>迁移说明：
 * <ul>
 *   <li>未知类型仍回退到 {@link #ACTIVITY}，这是为了兼容旧图和缺省图定义。</li>
 *   <li>新图建议显式声明类型；这里的兜底只是一种迁移兼容策略，不是新的建模默认。</li>
 * </ul>
 * 这样可以减少历史数据导入时的失败面，同时保持行为可预测。</p>
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

        throw new IllegalArgumentException(
            "Unknown node type '" + name + "'. " +
            "Valid types: START, END, ACTIVITY, EXCLUSIVE, INCLUSIVE, PARALLEL, LOOP."
        );
    }
}
