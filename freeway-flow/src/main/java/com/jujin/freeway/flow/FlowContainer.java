package com.jujin.freeway.flow;

/**
 * Flow component container (used to look up TaskComponent / ConditionComponent)
 */
public interface FlowContainer {
    /**
     * Gets a component
     */
    Object component(String componentName);
}
