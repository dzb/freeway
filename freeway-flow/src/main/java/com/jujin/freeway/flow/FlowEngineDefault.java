package com.jujin.freeway.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default {@link FlowEngine}: an iterative interpreter of the v3 DAG.
 *
 * <p>Each evaluation walks a frontier (an explicit work stack) from the
 * graph's START: a node runs, its matched successors join the frontier, and
 * the walk repeats. Nothing recurses per node — a 10,000-node chain costs no
 * more JVM stack than a 10-node one — and nesting exists only where the
 * author created it (loop iterations, sequential parallel branches,
 * sub-graph calls), never as an artifact of path length.</p>
 *
 * <p>The seven node semantics, each pinned by {@code FlowEngineTest}:
 * <ul>
 *   <li><b>START</b> — entry; fires its hooks and advances along matched links.</li>
 *   <li><b>END</b> — marks the graph as completed; a run without it that was
 *       not stopped on purpose fails the evaluation.</li>
 *   <li><b>ACTIVITY</b> — writes its {@code data}, runs its task, fans out.</li>
 *   <li><b>EXCLUSIVE</b> — exactly one branch: first matching condition, else
 *       the default link; matching nothing is a dead end, reported loudly.</li>
 *   <li><b>INCLUSIVE</b> — join then fan: activates once every arriving
 *       branch has counted in, then routes all matching links.</li>
 *   <li><b>PARALLEL</b> — join then fork: branches run on the driver's
 *       executor (or inline without one); {@code join: "merge"} (default)
 *       isolates each branch's writes and merges them with conflict
 *       detection, {@code join: "shared"} opts out.</li>
 *   <li><b>LOOP</b> — with {@code $for}/{@code $in} it iterates its body
 *       sequentially per item, re-arming body joins each iteration; without
 *       {@code $for} it is a plain activity gateway.</li>
 * </ul>
 * Failure taxonomy is deliberate: configuration errors (unknown graph, bad
 * reference, ambiguous component) propagate as {@link IllegalArgumentException}
 * or {@link IllegalStateException}; execution failures surface as
 * {@link FlowException}.</p>
 */
public final class FlowEngineDefault implements FlowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(FlowEngineDefault.class);

    /**
     * Upper bound for LOOP iterations driven by {@code $in}: iterations run
     * sequentially, so a misconfigured or unbounded collection would otherwise
     * spin here forever.
     */
    static final int MAX_LOOP_ITERATIONS = 100_000;

    private final Map<String, Graph> graphMap = new ConcurrentHashMap<>();
    private final Map<String, FlowDriver> drivers;
    private final List<FlowInterceptor> interceptors;

    public FlowEngineDefault(Map<String, FlowDriver> drivers,
            List<FlowInterceptor> interceptors) {
        this.drivers = Map.copyOf(Objects.requireNonNull(drivers, "drivers"));
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors"));
    }

    @Override
    public FlowDriver driver(Graph graph) {
        Objects.requireNonNull(graph, "graph is null");
        String driverName = graph.driver();
        final String lookup = (driverName == null || driverName.isBlank()) ? "default" : driverName;
        FlowDriver driver = drivers.get(lookup);
        if (driver == null) {
            throw new IllegalArgumentException(
                "No driver found for: '" + lookup + "'. " +
                "Register drivers via FlowEngine.create(Map.of(\"id\", driver)) or " +
                "binder.contribute(FlowDriver.class).add(id, driver)");
        }
        return driver;
    }

    // --- graph management ---

    @Override
    public void load(Graph graph) {
        String id = Objects.requireNonNull(graph, "graph").id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Graph id must not be blank");
        }
        if (graphMap.putIfAbsent(id, graph) != null) {
            throw new IllegalArgumentException("Graph already loaded: " + id);
        }
    }

    @Override
    public void unload(String graphId) { graphMap.remove(graphId); }

    @Override
    public Collection<Graph> graphs() {
        // A snapshot: a live view would let a caller's iteration see graphs
        // load/unload mid-flight.
        return List.copyOf(graphMap.values());
    }

    @Override
    public Graph graph(String graphId) { return graphMap.get(graphId); }

    // --- eval ---

    @Override
    public void eval(Graph graph, FlowContext context) throws FlowException {
        Objects.requireNonNull(context, "context");
        // A fresh top-level run owns its context: a reused one must not carry
        // a previous run's stop flag or subscriptions into it.
        context.stopped(false);
        FlowExchanger exchanger = new FlowExchanger(graph, this, driver(graph), context);
        eval(graph, exchanger);
    }

    @Override
    public void eval(Graph graph, FlowExchanger exchanger) throws FlowException {
        if (!exchanger.isSubgraphEval()) {
            exchanger.context().eventBus().clear();
        }
        FlowInterceptor.FlowChain walk = () -> this.walk(exchanger, graph.start());
        runChain(0, exchanger.context(), graph, walk);

        // A gateway dead end (EXCLUSIVE with no match/default, or a join that
        // never received all its branches) must not report success: the graph
        // never reached its END node. Stopped runs are exempt — stopping is
        // intentional. The check also covers a no-op chain: an interceptor
        // that never proceeds leaves the graph un-run, which is indistinguish-
        // able from a veto and therefore legal.
        if (!exchanger.isStopped()) {
            ExecState.DeadEnd deadEnd = exchanger.execState().deadEnd();
            if (deadEnd != null) {
                throw new FlowException(
                    "Graph '" + graph.id() + "' did not complete: dead end at node '"
                        + deadEnd.nodeId() + "' in graph '" + deadEnd.graphId()
                        + "' (an EXCLUSIVE node matched no condition/default link, "
                        + "or a join gateway never received all its incoming branches)"
                );
            }
            // Completed run: drop flow-scoped subscriptions so a reused
            // context cannot carry this run's subscribers into the next.
            exchanger.context().eventBus().clear();
        }
    }

    private void runChain(int index, FlowContext context, Graph graph,
            FlowInterceptor.FlowChain leaf) throws FlowException {
        if (index >= interceptors.size()) {
            leaf.proceed();
            return;
        }
        interceptors.get(index).interceptFlow(
            context, graph, () -> runChain(index + 1, context, graph, leaf));
    }

    // --- the frontier walk ---

    private void walk(FlowExchanger exchanger, Node entry) throws FlowException {
        ArrayDeque<Node> frontier = new ArrayDeque<>();
        if (entry != null) {
            frontier.push(entry);
        }
        while (!frontier.isEmpty()) {
            Node node = frontier.pop();
            if (exchanger.isStopped()) {
                return;
            }
            switch (node.type()) {
                case START -> {
                    if (nodeHooks(exchanger, node)) fanOut(exchanger, node, frontier);
                }
                case END -> {
                    if (onNodeStart(exchanger, node)) {
                        exchanger.markEnded(node.graph());
                        onNodeEnd(exchanger, node);
                    }
                }
                case ACTIVITY -> {
                    if (taskExec(exchanger, node)) fanOut(exchanger, node, frontier);
                }
                case EXCLUSIVE -> {
                    if (taskExec(exchanger, node)) exclusiveOut(exchanger, node, frontier);
                }
                case INCLUSIVE -> {
                    if (joinArrived(exchanger, node) && taskExec(exchanger, node)) {
                        fanOut(exchanger, node, frontier);
                    }
                }
                case PARALLEL -> {
                    if (joinArrived(exchanger, node) && taskExec(exchanger, node)) {
                        parallelOut(exchanger, node);
                    }
                }
                case LOOP -> loopRun(exchanger, node, frontier);
            }
        }
    }

    /** START/END lifecycle: the hook pair only, no task, no condition. */
    private boolean nodeHooks(FlowExchanger exchanger, Node node) {
        if (!onNodeStart(exchanger, node)) return false;
        return onNodeEnd(exchanger, node);
    }

    /** And-fan-out: every link whose condition matches joins the frontier, in link order. */
    private void fanOut(FlowExchanger exchanger, Node node, ArrayDeque<Node> frontier)
            throws FlowException {
        List<Node> matched = new ArrayList<>();
        for (Link link : node.nextLinks()) {
            if (conditionTest(exchanger, link.when(), true)) {
                matched.add(link.nextNode());
            }
        }
        // Reverse push: the frontier pops in declared link order.
        for (int i = matched.size() - 1; i >= 0; i--) {
            frontier.push(matched.get(i));
        }
    }

    /** Xor-fan-out: first matching link wins; else the default; else dead end. */
    private void exclusiveOut(FlowExchanger exchanger, Node node, ArrayDeque<Node> frontier)
            throws FlowException {
        Link defLine = null;
        for (Link link : node.nextLinks()) {
            if (link.when().isEmpty()) {
                if (defLine != null) {
                    LOG.warn(
                        "EXCLUSIVE node '{}/{}' has multiple default (unconditional) links — using the last one",
                        node.graph().id(), node.id()
                    );
                }
                defLine = link;
            } else if (conditionTest(exchanger, link.when(), false)) {
                frontier.push(link.nextNode());
                return;
            }
        }
        if (defLine != null) {
            frontier.push(defLine.nextNode());
        } else {
            LOG.warn(
                "EXCLUSIVE node '{}/{}' matched no condition and has no default link — execution stops at this node",
                node.graph().id(), node.id()
            );
            markDeadEnd(exchanger, node);
        }
    }

    /**
     * Join bookkeeping shared by INCLUSIVE and PARALLEL: every incoming
     * branch counts in, and the gateway activates exactly on the arrival
     * that completes it — earlier arrivals are provisional dead ends (cleared
     * on activation) so a run that completes without full arrival fails
     * loudly instead of silently. The counter re-arms at activation so a
     * fork-join inside a LOOP body works again next iteration.
     */
    private boolean joinArrived(FlowExchanger exchanger, Node node) {
        int expected = node.prevLinks().size();
        int arrived = exchanger.execState().countIncr(node.graph(), node.id());
        if (arrived >= expected) {
            exchanger.execState().countSet(node.graph(), node.id(), 0);
            exchanger.execState().deadEndClear(node.graph(), node.id());
            return true;
        }
        markDeadEnd(exchanger, node);
        return false;
    }

    private void markDeadEnd(FlowExchanger exchanger, Node node) {
        exchanger.execState().deadEnd(node.graph(), node.id());
    }

    /** Task lifecycle: hooks around the node's condition-gated data + task, exactly-one-end. */
    private boolean taskExec(FlowExchanger exchanger, Node node) throws FlowException {
        boolean ended = false;
        try {
            // onNodeStart runs inside the try so the pairing below is
            // unconditional: whether it succeeds, returns false (stopped) or
            // throws, onNodeEnd is invoked exactly once — otherwise
            // interceptors and drivers maintaining per-node state (e.g. a
            // nesting stack) leak a start without an end.
            if (!onNodeStart(exchanger, node)) return false;

            if (conditionTest(exchanger, node.when(), true)) {
                if (!node.data().isEmpty()) {
                    exchanger.context().putAll(node.data());
                }
                try {
                    exchanger.driver().handleTask(exchanger, node.task());
                } catch (FlowException e) {
                    throw e;
                } catch (IllegalStateException | IllegalArgumentException e) {
                    throw e; // configuration errors — preserve original type
                } catch (Throwable e) {
                    throw new FlowException(
                        FlowException.TASK_FAILED + ": " + node.graph().id() + " / " + node.id(), e);
                }
            }

            if (exchanger.isStopped()) return false;
            ended = true;
            return onNodeEnd(exchanger, node);
        } finally {
            if (!ended) {
                try {
                    onNodeEnd(exchanger, node);
                } catch (Exception ex) {
                    LOG.warn("onNodeEnd failed after task failure at {}/{}",
                        node.graph().id(), node.id(), ex);
                }
            }
        }
    }

    private boolean onNodeStart(FlowExchanger exchanger, Node node) {
        for (FlowInterceptor interceptor : interceptors) {
            interceptor.onNodeStart(exchanger.context(), node);
        }
        exchanger.driver().onNodeStart(exchanger, node);
        return !exchanger.isStopped();
    }

    private boolean onNodeEnd(FlowExchanger exchanger, Node node) {
        for (FlowInterceptor interceptor : interceptors) {
            interceptor.onNodeEnd(exchanger.context(), node);
        }
        exchanger.driver().onNodeEnd(exchanger, node);
        return !exchanger.isStopped();
    }

    private boolean conditionTest(FlowExchanger exchanger, ConditionDesc condition, boolean def)
            throws FlowException {
        if (condition.isEmpty()) return def;
        try {
            return exchanger.driver().handleCondition(exchanger, condition);
        } catch (FlowException e) {
            throw e;
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw e; // configuration errors — preserve original type
        } catch (Throwable e) {
            throw new FlowException("The condition handle failed: "
                + condition.graph().id() + " / " + condition.description(), e);
        }
    }

    // --- PARALLEL ---

    private void parallelOut(FlowExchanger exchanger, Node node) throws FlowException {
        List<Node> branches = node.nextNodes();
        if (exchanger.driver().executor() == null || branches.size() < 2) {
            // No executor (or nothing to overlap): branches run inline, each
            // still isolated and merged in order.
            for (Node branch : branches) {
                runBranch(exchanger, node, branch);
            }
            return;
        }
        // NESTED PARALLEL HAZARD: the join awaits on the calling thread. A
        // fixed-size pool deadlocks when graphs nest PARALLEL nodes (outer
        // branches occupy all workers while inner branches sit queued). Use a
        // cached/unbounded executor, or size the pool >= worst-case concurrent
        // branches.
        CountDownLatch latch = new CountDownLatch(branches.size());
        // First failure wins (CAS): deterministic error reporting, and the
        // fast-path bail below lets queued branches skip work early.
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        for (Node branch : branches) {
            try {
                exchanger.driver().executor().execute(() -> {
                    try {
                        if (errorRef.get() != null) return;
                        runBranch(exchanger, node, branch);
                    } catch (Throwable ex) {
                        errorRef.compareAndSet(null, ex);
                    } finally {
                        latch.countDown();
                    }
                });
            } catch (RejectedExecutionException rejected) {
                // Executor no longer accepting work (shutting down): record
                // and release this branch's latch slot so await() cannot hang
                // on work that will never be scheduled.
                errorRef.compareAndSet(null, rejected);
                latch.countDown();
            }
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlowException("Parallel execution interrupted", e);
        }
        Throwable ex = errorRef.get();
        if (ex != null) {
            // Match the inline path's exception-type contract: configuration
            // errors stay distinguishable from execution failures.
            if (ex instanceof FlowException fe) throw fe;
            if (ex instanceof IllegalStateException || ex instanceof IllegalArgumentException) {
                throw (RuntimeException) ex;
            }
            throw new FlowException(ex);
        }
    }

    /**
     * One branch of a PARALLEL fork. Unless the fork declares
     * {@code join: "shared"}, the branch runs over a thread-local write
     * buffer and merges into the parent on clean completion — concurrent
     * branches then cannot race on a key, and a genuine write-write conflict
     * fails the run with both values named. A branch that stopped or failed
     * contributes nothing (its partial writes are discarded with it).
     */
    private void runBranch(FlowExchanger exchanger, Node fork, Node branch) throws FlowException {
        if ("shared".equals(fork.metaAsString("join"))) {
            walk(exchanger, branch);
            return;
        }
        Runnable merge = exchanger.context().beginBranch();
        // A throwing walk never reaches the merger: an aborted branch
        // contributes nothing, its partial writes discarded with it.
        walk(exchanger, branch);
        merge.run();
    }

    // --- LOOP ---

    private void loopRun(FlowExchanger exchanger, Node node, ArrayDeque<Node> frontier)
            throws FlowException {
        String forKey = node.metaAsString("$for");
        if (forKey == null) {
            // No $in/$for pair: a plain activity gateway (iteration is undefined).
            if (taskExec(exchanger, node)) fanOut(exchanger, node, frontier);
            return;
        }

        ArrayDeque<Iterator<?>> stack = exchanger.execState().loopStack(node.graph(), node.id());
        Iterator<?> iterator;
        synchronized (stack) {
            // Claim atomically: the "sibling already running this loop" check,
            // the fresh iterator and the push happen under one monitor, so two
            // branches reaching this node cannot both run the body — later
            // arrivals skip the whole node. An exhausted iterator left by a
            // completed run is popped first, re-arming a sequential re-entry.
            if (loopBusy(stack)) return;
            iterator = loopIterator(exchanger, node);
            stack.push(iterator);
        }

        if (!taskExec(exchanger, node)) return;

        // Guard against unbounded iteration: a misconfigured $in (e.g. a
        // multi-million-element collection) would otherwise spin here forever.
        int iterations = 0;
        while (iterator.hasNext()) {
            if (++iterations > MAX_LOOP_ITERATIONS) {
                throw new FlowException(
                    "LOOP iteration limit exceeded (max " + MAX_LOOP_ITERATIONS
                        + ") at graph '" + node.graph().id() + "' / node '" + node.id()
                        + "' — check '$in' for an oversized or unbounded collection"
                );
            }
            exchanger.context().put(forKey, iterator.next());
            // A new iteration re-enters the body: join counters (and their
            // provisional dead-ends) left by the previous iteration must not
            // leak into this one.
            resetLoopBodyJoins(exchanger, node);
            // The body runs to completion per iteration (a nested walk per
            // matched link), not round-robin across iterations.
            List<Node> body = new ArrayList<>();
            for (Link link : node.nextLinks()) {
                if (conditionTest(exchanger, link.when(), true)) {
                    body.add(link.nextNode());
                }
            }
            for (Node member : body) {
                walk(exchanger, member);
            }
        }
    }

    /** True when the loop's top iterator still has items — caller holds the monitor. */
    private static boolean loopBusy(ArrayDeque<Iterator<?>> stack) {
        Iterator<?> top = stack.peek();
        if (top != null) {
            if (top.hasNext()) return true;
            stack.pop();
        }
        return false;
    }

    /**
     * Resolves the {@code $in} meta into an iterator: a list, an iterable, a
     * context key holding either, or a {@code "start...end"} /
     * {@code "start:end:step"} range string.
     */
    private Iterator<?> loopIterator(FlowExchanger exchanger, Node node) {
        Object inKey = node.meta("$in");

        Object inObj;
        if (inKey instanceof List) {
            inObj = inKey;
        } else if (inKey instanceof String inKeyStr) {
            if (!inKeyStr.contains(":") && !inKeyStr.contains("...")) {
                inObj = exchanger.context().getAs(inKeyStr);
            } else {
                inObj = com.jujin.freeway.flow.internal.Stepper.from(inKeyStr);
            }
        } else {
            throw new FlowException(
                "Node '" + node.id() + "' has $in=" + inKey
                    + " — expected a list, a context key holding one, or a"
                    + " \"start...end\" / \"start:end:step\" range string"
            );
        }

        if (inObj instanceof Iterator) return (Iterator<?>) inObj;
        if (inObj instanceof Iterable) return ((Iterable<?>) inObj).iterator();
        throw new FlowException(
            "Node '" + node.id() + "' resolves $in=" + inKey + " to "
                + (inObj == null ? "nothing" : inObj.getClass().getName())
                + " — the context key must hold a List/Iterable, or $in must be a"
                + " list literal or a range string"
        );
    }

    /**
     * Resets the inclusive/parallel join bookkeeping inside this LOOP's body
     * at the start of every iteration. A join that received fewer arrivals
     * than expected in one iteration never reset its counter (only activation
     * does), so the next iteration could falsely activate early or twice. The
     * provisional dead-end is cleared too — a new iteration starts fresh.
     */
    private void resetLoopBodyJoins(FlowExchanger exchanger, Node loopNode) {
        List<String> joins = exchanger.execState().loopBodyJoins(
            loopNode.graph(), loopNode.id(), () -> loopBodyJoins(loopNode));
        Graph graph = loopNode.graph();
        for (String joinId : joins) {
            exchanger.execState().countSet(graph, joinId, 0);
            exchanger.execState().deadEndClear(graph, joinId);
        }
    }

    /**
     * Join nodes (INCLUSIVE/PARALLEL with more than one incoming link)
     * reachable from the LOOP node's outgoing links — the gateways executed
     * inside each iteration of the body.
     */
    private static List<String> loopBodyJoins(Node loopNode) {
        Graph graph = loopNode.graph();
        List<String> joins = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<Node> queue = new ArrayDeque<>();
        for (Link link : loopNode.nextLinks()) queue.add(link.nextNode());
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (!visited.add(n.id())) continue;
            if (n == loopNode) continue;               // cycle safety (graphs are DAGs)
            if (n.type() == NodeType.END) continue;    // the body ends at END
            if ((n.type() == NodeType.INCLUSIVE || n.type() == NodeType.PARALLEL)
                    && n.prevLinks().size() > 1) {
                joins.add(n.id());
            }
            for (Link link : n.nextLinks()) queue.add(link.nextNode());
        }
        return joins;
    }
}
