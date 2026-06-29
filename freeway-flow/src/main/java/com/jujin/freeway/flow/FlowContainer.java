package com.jujin.freeway.flow;

/**
 * 流组件容器（用于查找 TaskComponent / ConditionComponent）
 *
 * @author noear
 * @since 3.1
 */
public interface FlowContainer {
    /**
     * 获取组件
     */
    Object getComponent(String componentName);
}
