package com.jujin.freeway.flow;

/**
 * Flow interceptor: cross-cutting observation and control of evaluations.
 * Contribute implementations to the container
 * ({@code binder.contribute(FlowInterceptor.class).add(...)}) — the engine
 * assembles the chain at startup and it cannot change while running.
 *
 * <p>Two levels:
 * <ul>
 *   <li>{@link #interceptFlow} — wraps an entire {@code eval} (including its
 *       sub-graph calls, which share the parent run)</li>
 *   <li>{@link #onNodeStart} / {@link #onNodeEnd} — per-node lifecycle; the
 *       engine guarantees exactly one end for every start</li>
 * </ul>
 *
 * <pre>{@code
 * binder.contribute(FlowInterceptor.class).add("audit", new FlowInterceptor() {
 *     @Override
 *     public void onNodeStart(FlowContext context, Node node) {
 *         context.eventBus().publish("audit", "→ " + node.id());
 *     }
 * });
 * }</pre>
 */
public interface FlowInterceptor {

    /**
     * Wraps the evaluation of one graph. Call {@code chain.proceed()} to run
     * the graph — not calling it skips the evaluation entirely (a legal,
     * intentional veto; the run completes without error).
     */
    default void interceptFlow(FlowContext context, Graph graph, FlowChain chain)
            throws FlowException {
        chain.proceed();
    }

    /** When a node run starts. */
    default void onNodeStart(FlowContext context, Node node) {
    }

    /** When a node run ends. */
    default void onNodeEnd(FlowContext context, Node node) {
    }

    /** Continuation of the interceptor chain. */
    @FunctionalInterface
    interface FlowChain {
        void proceed() throws FlowException;
    }
}
