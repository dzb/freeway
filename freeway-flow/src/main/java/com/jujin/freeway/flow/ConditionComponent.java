package com.jujin.freeway.flow;

/**
 * Condition component
 */
@FunctionalInterface
public interface ConditionComponent {
    /**
     * Tests
     *
     * @param context the flow context
     */
    boolean test(FlowContext context) throws Throwable;
}
