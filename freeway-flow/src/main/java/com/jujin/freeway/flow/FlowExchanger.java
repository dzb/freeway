package com.jujin.freeway.flow;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The state of one evaluation in flight: which graph and context are being
 * run, which driver resolves their tasks, and the {@link ExecState} joining
 * them. Custom drivers and {@code #subgraph} calls receive one of these;
 * everything on it is the current run, never the definition.
 *
 * <p>A {@link #copy(Graph)} switches the graph (and optionally the context)
 * while keeping the evaluation alive — that is how a sub-graph call joins
 * the parent's counters and dead-end reporting.</p>
 */
public final class FlowExchanger {
    private final Graph graph;
    private final FlowEngine engine;
    private final FlowDriver driver;
    private final FlowContext context;
    final ExecState execState;
    /** True when this exchanger runs a sub-graph (created by {@link #runGraph}). */
    private volatile boolean subgraphEval = false;
    /** Graphs whose END node was reached in this evaluation. */
    private final Set<String> graphEnded = ConcurrentHashMap.newKeySet();

    public FlowExchanger(Graph graph, FlowEngine engine, FlowDriver driver,
            FlowContext context) {
        this(graph, engine, driver, context, new ExecState());
    }

    FlowExchanger(Graph graph, FlowEngine engine, FlowDriver driver,
            FlowContext context, ExecState execState) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.context = Objects.requireNonNull(context, "context");
        this.execState = Objects.requireNonNull(execState, "execState");
    }

    /** The same evaluation continued on another graph, sharing the run state. */
    public FlowExchanger copy(Graph graphNew) {
        return new FlowExchanger(graphNew, engine, driver, context, execState);
    }

    /** The same evaluation continued on another graph and context (e.g. a
     *  sub-graph run with isolated variables). */
    public FlowExchanger copy(Graph graphNew, FlowContext contextNew) {
        return new FlowExchanger(graphNew, engine, driver, contextNew, execState);
    }

    public Graph graph() { return graph; }
    public FlowEngine engine() { return engine; }
    public FlowDriver driver() { return driver; }
    public FlowContext context() { return context; }

    // --- sub-graph ---

    /**
     * Runs a sub-graph as part of this evaluation: the sub-graph resolves
     * its own driver, shares this run's join counters and dead-end
     * reporting, and must reach its END — a sub-graph that stops early is a
     * {@link FlowException} at the calling node, not a silent success.
     */
    public void runGraph(Graph graph) {
        FlowExchanger subEx = new FlowExchanger(
            graph, engine, engine.driver(graph), context, execState);
        subEx.markSubgraphEval();
        engine.eval(graph, subEx);
        if (!isStopped() && !subEx.isGraphEnded(graph.id())) {
            throw new FlowException(
                "Sub-graph '" + graph.id() + "' did not reach its END node "
                    + "(a gateway took no branch, or an interceptor stopped "
                    + "the sub-run) — the calling flow cannot continue");
        }
    }

    /**
     * Runs the given node's task through its graph's driver, wrapping any
     * non-{@link FlowException} failure as {@code TASK_FAILED}. Entry point
     * for custom drivers that execute tasks outside the normal dispatch.
     */
    public void runTask(Node node, String description) throws FlowException {
        Objects.requireNonNull(node, "node");
        try {
            engine.driver(node.graph())
                .handleTask(this, new TaskDesc(node, description));
        } catch (FlowException e) {
            throw e;
        } catch (Throwable e) {
            throw new FlowException(
                FlowException.TASK_FAILED + ": " + node.graph().id() + " / " + node.id(), e);
        }
    }

    // --- run control ---

    /** Ends the run at the next node boundary (intentional early completion). */
    public void stop() {
        context.stopped(true);
    }

    public boolean isStopped() {
        return context.isStopped();
    }

    // --- engine-internal ---

    ExecState execState() { return execState; }

    /** True when this exchanger runs a sub-graph (created by {@link #runGraph}). */
    boolean isSubgraphEval() { return subgraphEval; }

    void markSubgraphEval() { this.subgraphEval = true; }

    /** Marks a graph as having reached its END node (see the END dispatch). */
    void markEnded(Graph graph) {
        graphEnded.add(graph.id());
    }

    boolean isGraphEnded(String graphId) {
        return graphEnded.contains(graphId);
    }
}
