package com.jujin.freeway.flow;

/**
 * Flow interceptor
 *
 * <p>Provides interception at two levels:
 * <ul>
 *   <li>{@link #interceptFlow(FlowInvocation)} — wraps the entire eval execution</li>
 *   <li>{@link #onNodeStart(FlowContext, Node)} / {@link #onNodeEnd(FlowContext, Node)} — per-node lifecycle</li>
 * </ul>
 *
 * <pre>{@code
 * engine.addInterceptor(new FlowInterceptor() {
 *     @Override
 *     public void interceptFlow(FlowInvocation inv) {
 *         System.out.println("started: " + inv.getGraph().getId());
 *         inv.invoke();
 *         System.out.println("execution complete");
 *     }
 *
 *     @Override
 *     public void onNodeStart(FlowContext ctx, Node node) {
 *         System.out.println("→ " + node.getId());
 *     }
 * });
 * }</pre>
 *
 * @author noear
 * @since 3.1
 */
public interface FlowInterceptor {

    /**
     * Intercepts flow execution (wraps the entire eval(graph) call)
     *
     * <p>{@code invocation.invoke()} must be called to continue the chain,
     * otherwise the flow will not actually execute.</p>
     */
    default void interceptFlow(FlowInvocation invocation) throws FlowException {
        invocation.invoke();
    }

    /**
     * When a node run starts
     */
    default void onNodeStart(FlowContext context, Node node) {
    }

    /**
     * When a node run ends
     */
    default void onNodeEnd(FlowContext context, Node node) {
    }
}
