package com.jujin.freeway.flow;

/**
 * 流拦截器
 *
 * <p>提供两个层面的拦截能力：
 * <ul>
 *   <li>{@link #interceptFlow(FlowInvocation)} — 环绕整个 eval 执行</li>
 *   <li>{@link #onNodeStart(FlowContext, Node)} / {@link #onNodeEnd(FlowContext, Node)} — 每个节点的生命周期</li>
 * </ul>
 *
 * <pre>{@code
 * engine.addInterceptor(new FlowInterceptor() {
 *     @Override
 *     public void interceptFlow(FlowInvocation inv) {
 *         System.out.println("开始执行: " + inv.getGraph().getId());
 *         inv.invoke();
 *         System.out.println("执行完成");
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
     * 拦截流程执行（环绕整个 eval(graph) 调用）
     *
     * <p>必须调用 {@code invocation.invoke()} 继续执行链，
     * 否则流程不会真正执行。</p>
     */
    default void interceptFlow(FlowInvocation invocation) throws FlowException {
        invocation.invoke();
    }

    /**
     * 节点运行开始时
     */
    default void onNodeStart(FlowContext context, Node node) {
    }

    /**
     * 节点运行结束时
     */
    default void onNodeEnd(FlowContext context, Node node) {
    }
}
