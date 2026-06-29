package com.jujin.freeway.flow;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * 流调用者（责任链模式，依次调用拦截器，最后到达引擎的 evalDo）
 *
 * @author noear
 * @since 3.1
 */
public class FlowInvocation {
    private final FlowExchanger exchanger;
    private final FlowOptions options;
    private final Node startNode;
    private final List<FlowOptions.RankedInterceptor> interceptorList;
    private final BiConsumer<FlowInvocation, FlowOptions> lastHandler;
    private int index;

    public FlowInvocation(FlowExchanger exchanger, FlowOptions options, Node startNode,
                          BiConsumer<FlowInvocation, FlowOptions> lastHandler) {
        this.exchanger = exchanger;
        this.options = options;
        this.startNode = startNode;
        this.interceptorList = options.getInterceptorList();
        this.lastHandler = lastHandler;
        this.index = 0;
    }

    /** 获取交换器 */
    public FlowExchanger getExchanger() { return exchanger; }

    /** 获取上下文 */
    public FlowContext getContext() { return exchanger.context(); }

    /** 获取图 */
    public Graph getGraph() { return startNode.getGraph(); }

    /** 获取起始节点（或恢复节点） */
    public Node getStartNode() { return startNode; }

    /**
     * 调用下一个拦截器；如果是最后一个，则执行真正的 evalDo
     */
    public void invoke() throws FlowException {
        if (index < interceptorList.size()) {
            interceptorList.get(index++).interceptor().interceptFlow(this);
        } else {
            lastHandler.accept(this, options);
        }
    }
}
