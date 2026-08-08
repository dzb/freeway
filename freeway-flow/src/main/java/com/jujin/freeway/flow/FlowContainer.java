package com.jujin.freeway.flow;

/**
 * Flow component container (used to look up TaskComponent / ConditionComponent)
 *
 * @author noear
 * @since 3.1
 */
public interface FlowContainer {
    /**
     * Gets a component
     */
    Object getComponent(String componentName);
}
