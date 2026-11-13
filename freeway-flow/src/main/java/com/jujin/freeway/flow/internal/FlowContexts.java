package com.jujin.freeway.flow.internal;

import com.jujin.freeway.flow.FlowContext;

/**
 * Internal access to flow-context execution state.
 *
 * <p>Public {@code FlowContext} implementations created through
 * {@code FlowContext.of(...)} implement {@link FlowContextState}. A custom
 * context passed directly to the engine must opt into the same internal state
 * contract when the engine needs to signal or reset a stop.</p>
 */
public final class FlowContexts {

    private FlowContexts() {}

    public static void setStopped(FlowContext context, boolean stopped) {
        if (context instanceof FlowContextState state) {
            state.setStopped(stopped);
            return;
        }
        throw new IllegalArgumentException(
            "FlowContext must implement FlowContextState to be driven by FlowEngine: "
                + context.getClass().getName());
    }
}
