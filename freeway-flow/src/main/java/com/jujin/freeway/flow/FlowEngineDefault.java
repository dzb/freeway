package com.jujin.freeway.flow;

import com.jujin.freeway.flow.internal.Stepper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link FlowEngine} implementation — the engine returned by
 * {@link FlowEngine#newInstance()} and bound as a singleton by
 * {@link FlowModule}.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>Node traversal, conditional branching, sub-graph calls and interceptor-chain logic are preserved, but execution-state control is explicitly consolidated within the engine instance.</li>
 *   <li>Drivers are injected via {@code Map<String, FlowDriver>} and matched by the graph's driver name (null/"" → "default").</li>
 *   <li>Pause, stop and revert flags belong to a single execution's state, enabling resume and continued execution after brief interruptions.</li>
 * </ul>
 * This keeps the ported engine's original behavior while satisfying Freeway's explicit assembly model.</p>
 *
 * @author noear
 * @since 3.0
 */
public class FlowEngineDefault implements FlowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(FlowEngineDefault.class);

    /**
     * Maximum node recursion depth during evaluation. The executor walks the
     * graph recursively (one frame per node), so pathologically deep chains
     * could exhaust the JVM stack. This limit fails fast with a clear error;
     * real-world flows rarely exceed a few hundred nodes. Deeper graphs should
     * be restructured or evaluated iteratively.
     */
    static final int MAX_EXECUTION_DEPTH = 1000;

    /**
     * Upper bound for LOOP node iterations driven by {@code $in}. Iterations
     * run sequentially inside a single frame, so the recursion depth guard
     * offers no protection against an oversized or unbounded collection.
     */
    static final int MAX_LOOP_ITERATIONS = 100_000;

    protected final Map<String, Graph> graphMap;
    protected final Map<String, FlowDriver> drivers;
    protected final FlowMarkerIndex markerIndex = new FlowMarkerIndex();
    protected final List<FlowOptions.RankedInterceptor> interceptorList;

    public FlowEngineDefault(Map<String, FlowDriver> drivers) {
        this.drivers = new HashMap<>(Objects.requireNonNull(drivers, "drivers"));
        // Concurrent: load()/unload() may run while other threads evaluate.
        this.graphMap = new ConcurrentHashMap<>();
        // Copy-on-write: addInterceptor/removeInterceptor may run while eval
        // snapshots the list (plain ArrayList would CME or publish a
        // partially-sorted list).
        this.interceptorList = new CopyOnWriteArrayList<>();
    }

    @Override
    public FlowDriver getDriver(Graph graph) {
        Objects.requireNonNull(graph, "graph is null");
        String driverName = graph.getDriver();
        final String lookup = (driverName == null || driverName.isBlank()) ? "default" : driverName;
        FlowDriver driver = drivers.get(lookup);
        if (driver == null) {
            throw new IllegalArgumentException(
                "No driver found for: '" + lookup + "'. " +
                "Register drivers via newInstance(Map.of(\"id\", driver)) or " +
                "binder.contribute(FlowDriver.class).add(id, driver)");
        }
        return driver;
    }

    @Override
    public void register(TaskComponent handler) {
        if (handler != null) {
            markerIndex.register(handler);
        }
    }

    @Override
    public FlowMarkerIndex markerIndex() {
        return markerIndex;
    }

    // --- interceptor ---

    @Override
    public void addInterceptor(FlowInterceptor interceptor, int index) {
        interceptorList.add(new FlowOptions.RankedInterceptor(interceptor, index));
        if (!interceptorList.isEmpty()) Collections.sort(interceptorList);
    }

    @Override
    public void removeInterceptor(FlowInterceptor interceptor) {
        interceptorList.removeIf(r -> r.interceptor() == interceptor);
    }

    // --- graph ---

    @Override
    public void load(Graph graph) {
        String id = Objects.requireNonNull(graph, "graph").getId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Graph id must not be blank");
        }
        if (graphMap.containsKey(id)) {
            throw new IllegalArgumentException("Graph already loaded: " + id);
        }
        graphMap.put(id, graph);
    }

    @Override
    public void unload(String graphId) { graphMap.remove(graphId); }

    @Override
    public Collection<Graph> getGraphs() { return graphMap.values(); }

    @Override
    public Graph getGraph(String graphId) { return graphMap.get(graphId); }

    // ==================== core eval ====================

    @Override
    public void eval(Graph graph, FlowExchanger exchanger, FlowOptions options) throws FlowException {
        Node lastNode = exchanger.context().trace().lastNode(graph);
        FlowExchanger bak = exchanger.context().exchanger();

        // Propagate the caller's per-eval options to sub-graph calls:
        // runGraph() reads this and re-passes it, so per-eval interceptors
        // (interceptFlow / onNodeStart / onNodeEnd) cover sub-graph nodes too.
        // Only the raw options are stored — the engine-level interceptor list
        // is merged per eval below, so it is never added twice on nested evals.
        exchanger.evalOptions(options);

        // A FRESH run (the trace holds no record for this graph) starts with
        // a clean event bus: a reused FlowContext must not keep firing stale
        // subscriptions from a paused/interrupted run of another graph (their
        // closures capture the old execution). Resuming the same graph keeps
        // its trace record and therefore its subscriptions; sub-graph evals
        // share the live bus and must never clear it mid-run.
        if (exchanger.context().trace().lastRecord(graph.getId()) == null
                && !exchanger.isSubgraphEval()) {
            exchanger.context().eventBus().clear();
        }

        // Defensive copy — never mutate the caller's FlowOptions instance
        FlowOptions opts = new FlowOptions();
        if (options != null) {
            opts.interceptorAdd(options.getInterceptorList());
        }
        opts.interceptorAdd(interceptorList);

        try {
            exchanger.context().exchanger(exchanger);
            exchanger.context().stopped(false);
            new FlowInvocation(exchanger, opts, lastNode, this::evalDo).invoke();
            // Completed (non-stopped, non-interrupted) evals release
            // flow-scoped event subscriptions so a reused FlowContext does
            // not accumulate stale subscribers across runs. Paused and
            // interrupted evals keep theirs — the run continues on resume.
            if (!exchanger.isStopped() && !exchanger.isInterrupted()) {
                exchanger.context().eventBus().clear();
                // A gateway dead end (EXCLUSIVE with no match/default, or a
                // join that never received all its branches) must not report
                // success: the graph never reached its END node. Stopped and
                // interrupted runs are exempt — stopping is intentional and
                // an interceptor-blocked run is not a dead end.
                ExecState.DeadEnd deadEnd = exchanger.execState().deadEnd();
                if (deadEnd != null) {
                    throw new FlowException(
                        "Graph '" + graph.getId() + "' did not complete: dead end at node '"
                            + deadEnd.nodeId() + "' in graph '" + deadEnd.graphId()
                            + "' (an EXCLUSIVE node matched no condition/default link, "
                            + "or a join gateway never received all its incoming branches)"
                    );
                }
            }
            // Resume replay walks from START with tasks skipped until the
            // traced node is reached. If gateway conditions changed since the
            // partial run, the walk may never reach it — silently returning
            // success with every task skipped would be worse than failing.
            // Only a genuinely interrupted run (trace holds a non-END node)
            // counts as resume: a fresh eval whose flow was blocked by an
            // interceptor legitimately ends with tasks never started.
            NodeRecord last = exchanger.context().trace().lastRecord(graph.getId());
            if (last != null && !last.isEnd()
                    && !exchanger.isStopped() && exchanger.isReverting()) {
                throw new FlowException(
                    "Unable to resume graph '" + graph.getId()
                        + "': the resume point was not reached during replay "
                        + "(a gateway condition may have changed since the "
                        + "interrupted run)"
                );
            }
        } catch (StackOverflowError e) {
            // Safety net for recursion the depth guard cannot see (e.g. a
            // user TaskComponent recursing) — surface it as a FlowException
            // instead of crashing the thread with an Error.
            throw new FlowException(
                "Graph execution exceeded the JVM stack depth ("
                    + graph.getId() + "); check for cycles or excessive nesting",
                e
            );
        } finally {
            exchanger.context().exchanger(bak);
        }
    }

    protected void evalDo(FlowInvocation inv, FlowOptions options) throws FlowException {
        node_run(inv.getExchanger(), options, inv.getStartNode().getGraph().getStart(), inv.getStartNode());
    }

    // ==================== lifecycle hooks ====================

    protected boolean onNodeStart(FlowExchanger exchanger, FlowOptions options, Node node) {
        return nodeHook(exchanger, options, node, true);
    }

    protected boolean onNodeEnd(FlowExchanger exchanger, FlowOptions options, Node node) {
        return nodeHook(exchanger, options, node, false);
    }

    /** Shared body of {@link #onNodeStart} / {@link #onNodeEnd} — they differ only in which hook is invoked. */
    private boolean nodeHook(FlowExchanger exchanger, FlowOptions options, Node node, boolean start) {
        if (exchanger.isReverting()) return true;

        if (start) {
            for (var ri : options.getInterceptorList()) {
                ri.interceptor().onNodeStart(exchanger.context(), node);
            }
            exchanger.driver().onNodeStart(exchanger, node);
        } else {
            for (var ri : options.getInterceptorList()) {
                ri.interceptor().onNodeEnd(exchanger.context(), node);
            }
            exchanger.driver().onNodeEnd(exchanger, node);
        }

        if (exchanger.isStopped()) return false;
        if (exchanger.isInterrupted()) {
            return false;
        }
        return true;
    }

    // ==================== condition / task ====================

    protected boolean condition_test(FlowExchanger exchanger, ConditionDesc condition, boolean def) throws FlowException {
        if (condition.isEmpty()) return def;
        try {
            return exchanger.driver().handleCondition(exchanger, condition);
        } catch (FlowException e) {
            throw e;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e; // configuration errors — preserve original type
        } catch (Throwable e) {
            throw new FlowException("The condition handle failed: " + condition.getGraph().getId() + " / " + condition.getDescription(), e);
        }
    }

    protected boolean task_exec(FlowExchanger exchanger, FlowOptions options, Node node) throws FlowException {
        if (exchanger.isReverting()) return true;

        boolean ended = false;
        try {
            // onNodeStart runs inside the try so the pairing below is
            // unconditional: whether it succeeds, returns false (stopped /
            // interrupted) or throws, onNodeEnd is invoked exactly once for
            // this node — otherwise interceptors/drivers maintaining per-node
            // state (e.g. a nesting stack) leak a start without an end.
            if (!onNodeStart(exchanger, options, node)) return false;

            if (condition_test(exchanger, node.getWhen(), true)) {
                try {
                    exchanger.driver().handleTask(exchanger, node.getTask());
                } catch (FlowException e) {
                    throw e;
                } catch (IllegalStateException | IllegalArgumentException e) {
                    throw e; // configuration errors — preserve original type
                } catch (Throwable e) {
                    throw new FlowException(FlowException.TASK_FAILED + ": " + node.getGraph().getId() + " / " + node.getId(), e);
                }
            }

            if (exchanger.isStopped()) return false;
            if (exchanger.isInterrupted()) return false;

            // Mark BEFORE invoking so a throwing onNodeEnd is not re-invoked
            // by the failure path below.
            ended = true;
            return onNodeEnd(exchanger, options, node);
        } finally {
            // Failure path: every onNodeStart must be paired with onNodeEnd,
            // otherwise interceptors/drivers maintaining per-node state
            // (e.g. a nesting stack) leak on the first exception. The end
            // hook failure is logged, never masking the original exception.
            if (!ended) {
                try {
                    onNodeEnd(exchanger, options, node);
                } catch (Exception ex) {
                    LOG.warn(
                        "onNodeEnd failed after task failure at {}/{}",
                        node.getGraph().getId(), node.getId(), ex
                    );
                }
            }
        }
    }

    // ==================== dispatcher ====================

    protected void node_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) throws FlowException {
        if (node == null) return;
        int depth = exchanger.enterNode();
        try {
            if (depth > MAX_EXECUTION_DEPTH) {
                throw new FlowException(
                    "Flow execution depth exceeded (max " + MAX_EXECUTION_DEPTH
                        + " nodes) at graph '" + node.getGraph().getId()
                        + "' / node '" + node.getId()
                        + "' — check for cycles or an excessively long chain"
                );
            }
            nodeRunBody(exchanger, options, node, startNode);
        } finally {
            exchanger.exitNode();
        }
    }

    /** The recursive traversal body — kept separate so the depth guard wraps every entry. */
    private void nodeRunBody(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) throws FlowException {
        if (exchanger.isStopped()) return;
        // interrupt() is global for the run: once set it stops every branch at
        // its next node boundary. Deliberately NOT cleared here — clearing on
        // first observation made which branches stop scheduling-dependent.
        if (exchanger.isInterrupted()) {
            return;
        }

        if (exchanger.isReverting()) {
            if (node.getId().equals(startNode.getId())
                    && node.getGraph().getId().equals(startNode.getGraph().getId())) {
                exchanger.reverting(false);
            }
        } else {
            exchanger.recordNode(node.getGraph(), node);
        }

        if (!exchanger.isReverting()) {
            if (!exchanger.nextStep(node)) {
                exchanger.stop();
                return;
            }
        }

        switch (node.getType()) {
            case START     -> start_run(exchanger, options, node, startNode);
            case END       -> end_run(exchanger, options, node, startNode);
            case ACTIVITY  -> activity_run(exchanger, options, node, startNode);
            case INCLUSIVE -> inclusive_run(exchanger, options, node, startNode);
            case EXCLUSIVE -> exclusive_run(exchanger, options, node, startNode);
            case PARALLEL  -> parallel_run(exchanger, options, node, startNode);
            case LOOP      -> loop_run(exchanger, options, node, startNode);
            // Defensive: a graph built through a path that bypasses v2
            // validation must fail loudly instead of silently dead-ending.
            case UNKNOWN   -> throw new FlowException(
                "Node '" + node.getId() + "' in graph '"
                    + node.getGraph().getId() + "' has UNKNOWN type");
        }
    }

    // ==================== START ====================

    protected void start_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!onNodeStart(exchanger, options, node)) return;
        if (!onNodeEnd(exchanger, options, node)) return;
        for (Link l : node.getNextLinks()) {
            if (condition_test(exchanger, l.getWhen(), true)) {
                node_run(exchanger, options, l.getNextNode(), startNode);
            }
        }
    }

    // ==================== END ====================

    protected void end_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!onNodeStart(exchanger, options, node)) return;
        // Direct completion signal — independent of the trace (which may be
        // disabled) and of trace reset semantics for repeated subgraph calls.
        exchanger.markEnded(node.getGraph());
        onNodeEnd(exchanger, options, node);
    }

    // ==================== ACTIVITY ====================

    protected void activity_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!task_exec(exchanger, options, node)) return;
        activity_run_out(exchanger, options, node, startNode);
    }

    protected void activity_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        for (Link l : node.getNextLinks()) {
            if (condition_test(exchanger, l.getWhen(), true)) {
                node_run(exchanger, options, l.getNextNode(), startNode);
            }
        }
    }

    // ==================== EXCLUSIVE ====================

    protected void exclusive_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!task_exec(exchanger, options, node)) return;
        exclusive_run_out(exchanger, options, node, startNode);
    }

    protected void exclusive_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        Link defLine = null;
        for (Link l : node.getNextLinks()) {
            if (l.getWhen().isEmpty()) {
                if (defLine != null) {
                    LOG.warn(
                        "EXCLUSIVE node '{}/{}' has multiple default (unconditional) links — using the last one",
                        node.getGraph().getId(), node.getId()
                    );
                }
                defLine = l;
            } else if (condition_test(exchanger, l.getWhen(), false)) {
                node_run(exchanger, options, l.getNextNode(), startNode);
                return;
            }
        }
        if (defLine != null) {
            node_run(exchanger, options, defLine.getNextNode(), startNode);
        } else {
            LOG.warn(
                "EXCLUSIVE node '{}/{}' matched no condition and has no default link — execution stops at this node",
                node.getGraph().getId(), node.getId()
            );
            // The run would otherwise "complete" without reaching END. Mark
            // the dead end so eval() can fail loudly. Skipped during resume
            // replay, which is only a walk to the resume point.
            markDeadEnd(exchanger, node);
        }
    }

    private void markDeadEnd(FlowExchanger exchanger, Node node) {
        if (!exchanger.isReverting()) {
            exchanger.execState().deadEnd(node.getGraph(), node.getId());
        }
    }

    // ==================== INCLUSIVE ====================

    protected void inclusive_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!inclusive_run_in(exchanger, node)) return;
        if (!task_exec(exchanger, options, node)) return;
        inclusive_run_out(exchanger, options, node, startNode);
    }

    protected boolean inclusive_run_in(FlowExchanger exchanger, Node node) {
        if (node.getPrevLinks().size() > 1) {
            // Join semantics: the gateway activates exactly once, when every
            // incoming branch has arrived (standard BPMN inclusive-join).
            // countIncr is per-eval (ExecState is fresh per evaluation), so
            // the Nth arrival activates it and earlier ones park. Branches
            // whose condition does not route them to the gateway would leave
            // the join incomplete — same limitation as before, now explicit.
            // The counter is reset on activation (like PARALLEL) so a loop
            // body containing the fork-join re-arms for its next iteration.
            synchronized (exchanger.execState().stack(node.getGraph(), "inclusive_run")) {
                int arrived = exchanger.execState().countIncr(node.getGraph(), node.getId());
                if (arrived >= node.getPrevLinks().size()) {
                    exchanger.execState().countSet(node.getGraph(), node.getId(), 0);
                    // All branches arrived — the join is not a dead end.
                    exchanger.execState().deadEndClear(node.getGraph(), node.getId());
                    return true;
                }
                // Still waiting for branches. This is normal mid-run, but if
                // eval completes without activation the graph never reached
                // END — record the provisional dead end so the completion
                // check can fail loudly. Skipped during resume replay (walk
                // only), and cleared above if the join later activates.
                markDeadEnd(exchanger, node);
                return false;
            }
        }
        return true;
    }

    protected void inclusive_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        List<Link> matched = new ArrayList<>();
        for (Link l : node.getNextLinks()) {
            if (condition_test(exchanger, l.getWhen(), true)) matched.add(l);
        }
        for (Link l : matched) node_run(exchanger, options, l.getNextNode(), startNode);
    }

    // ==================== PARALLEL ====================

    protected void parallel_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (!parallel_run_in(exchanger, node)) return;
        if (!task_exec(exchanger, options, node)) return;
        parallel_run_out(exchanger, options, node, startNode);
    }

    protected boolean parallel_run_in(FlowExchanger exchanger, Node node) {
        int count = exchanger.execState().countIncr(node.getGraph(), node.getId());
        if (node.getPrevLinks().size() <= count) {
            // All branches arrived — the join is not a dead end.
            exchanger.execState().deadEndClear(node.getGraph(), node.getId());
            return true;
        }
        // Still waiting for branches — provisional dead end, same contract as
        // the INCLUSIVE join above.
        markDeadEnd(exchanger, node);
        return false;
    }

    protected void parallel_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        // Branches share the same FlowContext — concurrent writes to the same
        // key are a known limitation (see docs/freeway-flow-parallel-context-isolation.md).
        //
        // NESTED PARALLEL HAZARD: the join awaits on the CALLING thread. If a
        // branch itself contains a PARALLEL node and the executor is a
        // fixed-size pool, outer branches can occupy every worker while
        // waiting on inner branches that are still queued — classic thread-
        // starvation deadlock. Use a cached/unbounded executor (or size the
        // pool >= worst-case concurrent branches) when graphs nest PARALLEL.
        exchanger.execState().countSet(node.getGraph(), node.getId(), 0);

        if (exchanger.driver().getExecutor() == null || node.getNextNodes().size() < 2) {
            for (Node n : node.getNextNodes()) node_run(exchanger, options, n, startNode);
        } else {
            CountDownLatch cdl = new CountDownLatch(node.getNextNodes().size());
            // First failure wins (CAS): deterministic error reporting, and the
            // fast-path bail below lets queued branches skip work early.
            AtomicReference<Throwable> errorRef = new AtomicReference<>();
            for (Node n : node.getNextNodes()) {
                try {
                    exchanger.driver().getExecutor().execute(() -> {
                        try {
                            if (errorRef.get() != null) return;
                            node_run(exchanger, options, n, startNode);
                        } catch (Throwable ex) {
                            errorRef.compareAndSet(null, ex);
                        } finally {
                            cdl.countDown();
                        }
                    });
                } catch (RejectedExecutionException rejected) {
                    // Executor no longer accepting work (shutting down):
                    // record and release this branch's latch slot so await()
                    // below cannot hang on work that will never be scheduled.
                    // Already-queued branches observe the recorded error via
                    // the fast-path check above and return immediately.
                    errorRef.compareAndSet(null, rejected);
                    cdl.countDown();
                }
            }
            try {
                cdl.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FlowException("Parallel execution interrupted", e);
            }
            if (errorRef.get() != null) {
                Throwable ex = errorRef.get();
                // Match the serial path's exception-type contract: configuration
                // errors (missing binding, bad marker) stay distinguishable.
                if (ex instanceof FlowException fe) throw fe;
                if (ex instanceof IllegalStateException || ex instanceof IllegalArgumentException) {
                    throw (RuntimeException) ex;
                }
                throw new FlowException(ex);
            }
        }
    }

    // ==================== LOOP ====================

    protected void loop_run(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        if (node.getMetaAsString("$for") == null) {
            if (!loop_run_in(exchanger, node)) return;
            if (!task_exec(exchanger, options, node)) return;
            activity_run_out(exchanger, options, node, startNode);
        } else {
            // $for LOOP: claim the node atomically before running it. The
            // claim (skip-if-running check + iterator acquisition + push)
            // happens under one lock, so two PARALLEL branches reaching this
            // node concurrently cannot both pass an empty-stack check and each
            // run the loop body — the first arrival runs it (task and body),
            // later arrivals skip the whole node.
            if (!loop_run_claim(exchanger, node)) return;
            if (!task_exec(exchanger, options, node)) return;
            loop_run_out(exchanger, options, node, startNode);
        }
    }

    protected boolean loop_run_in(FlowExchanger exchanger, Node node) {
        Stack<Iterator> stack = exchanger.execState().stack(node.getGraph(), "loop_run/" + node.getId());
        // Atomic peek→hasNext→pop: a LOOP node reachable from concurrent
        // PARALLEL branches shares this stack.
        synchronized (stack) {
            if (loopBusy(stack)) return false;
        }
        return true;
    }

    /**
     * True when the top of the loop stack still has items — i.e. a sibling
     * branch is currently running this $for LOOP. An exhausted iterator left
     * by a completed run is popped so a later re-entry re-arms the loop.
     * Must be called while holding the stack monitor ({@code loop_run_in} /
     * {@code loop_run_claim} invoke it inside their synchronized blocks).
     */
    private boolean loopBusy(Stack<Iterator> stack) {
        if (!stack.isEmpty()) {
            Iterator<?> iter = stack.peek();
            if (iter.hasNext()) return true;
            stack.pop();
        }
        return false;
    }

    /**
     * Atomically claims this $for LOOP for the current arrival: the
     * "is another branch already running it?" check, the {@code $in} iterator
     * acquisition and the stack push all happen under the same stack monitor.
     * Returns {@code false} when a sibling branch is already running the loop
     * — the arrival then skips the node entirely.
     *
     * <p>An exhausted iterator left by a completed run is popped first, so a
     * later sequential re-entry (e.g. this node inside another loop's body)
     * re-arms the loop with a fresh run. Dead-end markers (EXCLUSIVE /
     * gateway joins) are intentionally untouched here.
     */
    protected boolean loop_run_claim(FlowExchanger exchanger, Node node) {
        Stack<Iterator> stack = exchanger.execState().stack(node.getGraph(), "loop_run/" + node.getId());
        synchronized (stack) {
            if (loopBusy(stack)) return false; // a sibling branch is running this loop
            stack.push(loop_iterator(exchanger, node));
        }
        return true;
    }

    /**
     * Resolves the {@code $in} meta into an iterator: a list, an iterable, a
     * context key holding either, or a {@code "start...end"} /
     * {@code "start:end:step"} range string.
     */
    protected Iterator<?> loop_iterator(FlowExchanger exchanger, Node node) {
        Object inKey = node.getMeta("$in");

        Object inObj;
        if (inKey instanceof List) {
            inObj = inKey;
        } else if (inKey instanceof String inKeyStr) {
            if (!inKeyStr.contains(":") && !inKeyStr.contains("...")) {
                inObj = exchanger.context().getAs(inKeyStr);
            } else {
                inObj = Stepper.from(inKeyStr);
            }
        } else {
            throw new FlowException("The '$in' must be a list or a string");
        }

        if (inObj instanceof Iterator) return (Iterator<?>) inObj;
        if (inObj instanceof Iterable) return ((Iterable<?>) inObj).iterator();
        throw new FlowException(inKey + " is not a collection");
    }

    protected void loop_run_out(FlowExchanger exchanger, FlowOptions options, Node node, Node startNode) {
        String forKey = node.getMetaAsString("$for");
        Stack<Iterator> stack = exchanger.execState().stack(node.getGraph(), "loop_run/" + node.getId());
        Iterator<?> iter;
        synchronized (stack) {
            // The claiming branch's iterator (pushed by loop_run_claim). The
            // stack keeps it until the next claim pops/replaces it, so
            // concurrent arrivals keep skipping while the loop is live.
            if (stack.isEmpty()) return; // defensive — loop_run_claim always precedes
            iter = stack.peek();
        }

        // Guard against unbounded iteration: a misconfigured or malicious
        // $in (e.g. a multi-million-element list) would otherwise spin here
        // forever — the recursion depth guard does not help because LOOP body
        // iterations run sequentially in a single frame.
        int iterations = 0;
        while (iter.hasNext()) {
            if (++iterations > MAX_LOOP_ITERATIONS) {
                throw new FlowException(
                    "LOOP iteration limit exceeded (max " + MAX_LOOP_ITERATIONS
                        + ") at graph '" + node.getGraph().getId()
                        + "' / node '" + node.getId()
                        + "' — check '$in' for an oversized or unbounded collection"
                );
            }
            Object item = iter.next();
            if (item == null) {
                // put() silently ignores null — remove instead so a null
                // element cannot leave the PREVIOUS iteration's value visible.
                exchanger.context().remove(forKey);
            } else {
                exchanger.context().put(forKey, item);
            }
            // A new iteration re-enters the body: join counters (and their
            // provisional dead-ends) left by the previous iteration must not
            // leak into this one — a fork-join that received fewer arrivals
            // last iteration would otherwise falsely activate early (or
            // twice) now.
            resetLoopBodyJoins(exchanger, node);
            activity_run_out(exchanger, options, node, startNode);
        }
    }

    // ==================== loop body join hygiene ====================

    /**
     * Resets the inclusive/parallel join bookkeeping inside this LOOP's body
     * at the start of every iteration. Counters keyed per (graph, node) would
     * otherwise carry residue across iterations: a join that received fewer
     * arrivals than expected in one iteration never reset its counter (only
     * activation does), so the next iteration could falsely activate early or
     * twice. The provisional dead-end is cleared too — a new iteration starts
     * fresh and re-records it if the join is still short.
     */
    private void resetLoopBodyJoins(FlowExchanger exchanger, Node loopNode) {
        String cacheKey = loopNode.getGraph().getId() + "/" + loopNode.getId();
        List<String> joins = exchanger.execState().loopBodyJoins(cacheKey, k -> loopBodyJoins(loopNode));
        Graph graph = loopNode.getGraph();
        for (String joinId : joins) {
            exchanger.execState().countSet(graph, joinId, 0);
            exchanger.execState().deadEndClear(graph, joinId);
        }
    }

    /**
     * Collects the ids of join nodes (INCLUSIVE/PARALLEL with more than one
     * incoming link) reachable from the LOOP node's outgoing links — i.e. the
     * gateways executed inside each iteration of the loop body. Computed once
     * per (graph, loop node) per evaluation and cached in {@link ExecState}.
     */
    private static List<String> loopBodyJoins(Node loopNode) {
        Graph graph = loopNode.getGraph();
        List<String> joins = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (Link l : loopNode.getNextLinks()) queue.add(l.getNextNode());
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (!visited.add(n.getId())) continue;
            if (n == loopNode) continue;               // cycle safety (graphs are DAGs)
            if (n.getType() == NodeType.END) continue; // the body ends at END
            if ((n.getType() == NodeType.INCLUSIVE || n.getType() == NodeType.PARALLEL)
                    && n.getPrevLinks().size() > 1) {
                joins.add(n.getId());
            }
            for (Link l : n.getNextLinks()) queue.add(l.getNextNode());
        }
        return joins;
    }
}
