package com.jujin.freeway.flow;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-execution flow exchanger.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>The execution state, context and step counter shared across sub-graph calls are consolidated into one runtime object instead of being scattered through the graph structure itself.</li>
 *   <li>{@code reverting}, {@code stopped} and {@code interrupted} are only control signals of the current execution; they are not written into the graph definition.</li>
 *   <li>{@code copy()} is provided to reuse the same execution state when switching sub-graphs, but with a different {@link Graph} or {@link FlowContext}.</li>
 * </ul>
 * This keeps the execution state controllable and replayable, and avoids polluting the model layer with runtime control flags.</p>
 *
 * @author noear
 * @since 3.0
 */
public class FlowExchanger {
    private final Graph graph;
    private final FlowEngine engine;
    private final FlowDriver driver;
    private FlowContext context;
    private final int steps;
    private final AtomicInteger stepCount;

    private final ExecState execState;
    /** Shared recursion depth guard — see {@link FlowEngineImpl#MAX_EXECUTION_DEPTH}. */
    private final AtomicInteger depth;
    /** Graphs whose END node was reached in this evaluation. */
    private final Set<String> graphEnded = ConcurrentHashMap.newKeySet();
    private volatile boolean interrupted = false;
    private volatile boolean stopped = false;
    private volatile boolean reverting = true;

    /**
     * The raw per-eval {@link FlowOptions} of the current evaluation, set by
     * {@link FlowEngineImpl#eval}. {@link #runGraph} re-passes it to the
     * sub-graph eval so per-eval interceptors cover sub-graph nodes too; only
     * the raw options are stored so the engine-level interceptor list is not
     * merged twice on nested evals.
     */
    private volatile FlowOptions evalOptions;

    /** True when this exchanger runs a sub-graph (created by {@link #runGraph}). */
    private volatile boolean subgraphEval = false;

    public FlowExchanger(Graph graph, FlowEngine engine, FlowDriver driver, FlowContext context, int steps, AtomicInteger stepCount) {
        this(graph, engine, driver, context, steps, stepCount, new ExecState(), new AtomicInteger());
    }

    private FlowExchanger(Graph graph, FlowEngine engine, FlowDriver driver, FlowContext context, int steps, AtomicInteger stepCount, ExecState execState, AtomicInteger depth) {
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(execState, "execState");

        this.graph = graph;
        this.engine = engine;
        this.driver = driver;
        this.context = context;
        this.steps = steps;
        this.stepCount = stepCount;
        this.execState = execState;
        this.depth = depth;
    }

    public FlowExchanger copy(Graph graphNew) {
        return new FlowExchanger(graphNew, engine, driver, context, steps, stepCount, execState, depth);
    }

    /** Enters a node — returns the current recursion depth. */
    int enterNode() {
        return depth.incrementAndGet();
    }

    /** Leaves a node. */
    void exitNode() {
        depth.decrementAndGet();
    }

    public Graph graph() { return graph; }
    public FlowEngine engine() { return engine; }
    public FlowDriver driver() { return driver; }
    public FlowContext context() { return context; }
    public ExecState execState() { return execState; }

    /** The raw per-eval options of the current evaluation (see {@link FlowEngineImpl#eval}). */
    public FlowOptions evalOptions() { return evalOptions; }

    /** Sets the raw per-eval options; called by {@link FlowEngineImpl#eval}. */
    void evalOptions(FlowOptions options) { this.evalOptions = options; }

    /** True when this exchanger runs a sub-graph (created by {@link #runGraph}). */
    public boolean isSubgraphEval() { return subgraphEval; }

    /** Marks this exchanger as a sub-graph evaluation. */
    void markSubgraphEval() { this.subgraphEval = true; }

    // --- trace ---

    public void recordNode(Graph graph, Node node) {
        context.trace().recordNode(graph, node);
    }

    // --- sub-graph ---

    public void runGraph(Graph graph) {
        prevStep(); // roll back the step count (sub-graph calls do not count as steps)
        // Reset the subgraph's trace entry BEFORE evaluation: eval resumes
        // from the traced node, so a second invocation of the same subgraph
        // would otherwise replay from its recorded END and silently skip the
        // body.
        context.trace().recordNode(graph, null);
        // Resolve the sub-graph's own driver — don't blindly reuse the parent's driver
        FlowExchanger subEx = new FlowExchanger(graph, engine,
            engine.getDriver(graph), context, steps, stepCount, execState, depth);
        // Sub-graph evals share the live event bus and trace — mark them so
        // eval() neither clears the parent's subscriptions nor treats the
        // (record-reset) subgraph as a fresh run.
        subEx.markSubgraphEval();
        // Propagate the parent eval's per-eval options (interceptors) so
        // sub-graph nodes are covered too; the sub-eval re-merges the
        // engine-level list exactly once (see FlowEngineImpl.eval).
        engine.eval(graph, subEx, evalOptions);

        if (!isStopped()) {
            // Completion is tracked on the exchanger (markEnded), not the
            // trace: trace may be disabled, and is reset per invocation.
            if (!subEx.isGraphEnded(graph.getId())) {
                interrupt(); // sub-graph did not end, interrupt the current branch
            }
        }
    }

    /** Marks a graph as having reached its END node (see {@link FlowEngineImpl#end_run}). */
    void markEnded(Graph graph) {
        graphEnded.add(graph.getId());
    }

    /**
     * True when this evaluation reached the graph's END node. Used instead of
     * the trace for subgraph completion — the trace may be disabled and is
     * reset between subgraph invocations.
     */
    boolean isGraphEnded(String graphId) {
        return graphEnded.contains(graphId);
    }

    // --- step control ---

    public void prevStep() {
        if (steps >= 0) stepCount.decrementAndGet();
    }

    public boolean nextStep(Node node) {
        if (steps < 0) return true;
        return stepCount.incrementAndGet() <= steps;
    }

    // --- stop / interrupt ---

    public boolean isStopped() {
        return stopped || context.isStopped();
    }

    public void stop() {
        stopped = true;
        context.stopped(true);
    }

    public boolean isInterrupted() { return interrupted; }

    public void interrupt() { this.interrupted = true; }

    // --- reverting ---

    public boolean isReverting() { return reverting; }

    public FlowExchanger reverting(boolean reverting) {
        this.reverting = reverting;
        return this;
    }
}
