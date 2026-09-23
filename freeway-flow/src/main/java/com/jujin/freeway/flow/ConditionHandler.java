package com.jujin.freeway.flow;

/**
 * Condition handler
 */
@FunctionalInterface
public interface ConditionHandler {
    /**
     * Tests
     *
     * @param context the flow context
     */
    boolean test(FlowContext context) throws Throwable;
}
