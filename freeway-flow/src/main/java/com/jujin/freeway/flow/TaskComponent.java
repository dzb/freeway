package com.jujin.freeway.flow;

/**
 * 任务组件
 *
 * @author noear
 * @since 3.0
 */
@FunctionalInterface
public interface TaskComponent {
    /**
     * 运行
     *
     * @param context 流上下文
     * @param node    当前节点
     */
    void run(FlowContext context, Node node) throws Throwable;
}
