package com.jujin.freeway.flow;

/**
 * Task handler
 */
@FunctionalInterface
public interface TaskHandler {
    /**
     * Runs
     *
     * @param context the flow context
     * @param node    the current node
     */
    void run(FlowContext context, Node node) throws Throwable;
}
