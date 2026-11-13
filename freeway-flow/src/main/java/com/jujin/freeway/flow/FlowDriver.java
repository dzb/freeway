package com.jujin.freeway.flow;

import java.util.concurrent.ExecutorService;

/**
 * Resolves a graph's conditions and tasks and supplies the executor for
 * PARALLEL fan-out. Custom drivers are contributed to the container
 * ({@code binder.contribute(FlowDriver.class).add(id, driver)}) and selected
 * per graph by the {@code driver} field.
 */
public interface FlowDriver {

    /** Async executor for PARALLEL node fan-out; null = branches run sequentially. */
    default ExecutorService executor() {
        return null;
    }

    /** When a node run starts. */
    default void onNodeStart(FlowExecution execution, Node node) {}

    /** When a node run ends. */
    default void onNodeEnd(FlowExecution execution, Node node) {}

    /** Evaluates a condition reference. */
    boolean handleCondition(FlowExecution execution, ConditionDesc condition) throws Throwable;

    /** Executes a task reference. */
    void handleTask(FlowExecution execution, TaskDesc task) throws Throwable;
}
