package com.jujin.freeway.flow;

/**
 * Task component
 */
@FunctionalInterface
public interface TaskComponent {
    /**
     * Runs
     *
     * @param context the flow context
     * @param node    the current node
     */
    void run(FlowContext context, Node node) throws Throwable;
}
