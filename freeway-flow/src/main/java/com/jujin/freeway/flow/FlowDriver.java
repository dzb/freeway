package com.jujin.freeway.flow;

import java.util.concurrent.ExecutorService;

/**
 * Flow driver
 *
 * @author noear
 * @since 3.0
 */
public interface FlowDriver {

    /** Async executor (for PARALLEL node concurrency) */
    default ExecutorService getExecutor() {
        return null;
    }

    /** When a node run starts */
    default void onNodeStart(FlowExchanger exchanger, Node node) {}

    /** When a node run ends */
    default void onNodeEnd(FlowExchanger exchanger, Node node) {}

    /** Handles condition evaluation */
    boolean handleCondition(FlowExchanger exchanger, ConditionDesc condition) throws Throwable;

    /** Handles a task */
    default void handleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable {
        postHandleTask(exchanger, task);
    }

    /** Post-handles a task */
    void postHandleTask(FlowExchanger exchanger, TaskDesc task) throws Throwable;
}
