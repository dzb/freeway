package com.jujin.freeway.flow;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Flow invocation (chain-of-responsibility: invokes interceptors in turn, finally reaching the engine's evalDo)
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

    /** Gets the exchanger */
    public FlowExchanger getExchanger() { return exchanger; }

    /** Gets the context */
    public FlowContext getContext() { return exchanger.context(); }

    /** Gets the graph */
    public Graph getGraph() { return startNode.getGraph(); }

    /** Gets the start node (or the resume node) */
    public Node getStartNode() { return startNode; }

    /**
     * Invokes the next interceptor; if it is the last one, runs the actual evalDo
     */
    public void invoke() throws FlowException {
        if (index < interceptorList.size()) {
            interceptorList.get(index++).interceptor().interceptFlow(this);
        } else {
            lastHandler.accept(this, options);
        }
    }
}
