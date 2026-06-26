package com.jujin.freeway.flow;

import java.util.concurrent.ExecutorService;

/**
 * 流驱动器
 *
 * @author noear
 * @since 3.0
 */
public interface FlowDriver {

    /** 异步执行器（用于 PARALLEL 节点并发） */
    default ExecutorService getExecutor() {
        return null;
    }

    /** 节点运行开始时 */
    default void onNodeStart(FlowExchanger exchanger, Node node) {}

    /** 节点运行结束时 */
    default void onNodeEnd(FlowExchanger exchanger, Node node) {}

    /** 处理条件检测 */
    boolean handleCondition(FlowExchanger exchanger, ConditionDesc condition) throws Throwable;

    /** 处理执行任务 */
    default void handleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        postHandleTask(exchanger, task);
    }

    /** 提交处理任务 */
    void postHandleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable;
}
