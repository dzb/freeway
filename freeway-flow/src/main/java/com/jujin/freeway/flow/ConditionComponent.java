package com.jujin.freeway.flow;

/**
 * Condition component
 *
 * @author noear
 * @since 3.7
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
