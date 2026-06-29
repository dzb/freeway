package com.jujin.freeway.flow;

/**
 * 条件组件
 *
 * @author noear
 * @since 3.7
 */
@FunctionalInterface
public interface ConditionComponent {
    /**
     * 检测
     *
     * @param context 流上下文
     */
    boolean test(FlowContext context) throws Throwable;
}
