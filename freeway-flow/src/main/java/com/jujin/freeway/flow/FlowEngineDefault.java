package com.jujin.freeway.flow;

import com.jujin.freeway.flow.internal.FlowContexts;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
 *       not stopped on purpose fails the execution.</li>
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
 * reference, ambiguous handler) propagate as {@link IllegalArgumentException}
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
    /** The module-level exit, or null for a standalone engine (see FlowEngine). */
    private final BiConsumer<String, Object> eventSink;

    public FlowEngineDefault(Map<String, FlowDriver> drivers,
            List<FlowInterceptor> interceptors) {
        this(drivers, interceptors, null);
    }

    public FlowEngineDefault(Map<String, FlowDriver> drivers,
            List<FlowInterceptor> interceptors, BiConsumer<String, Object> eventSink) {
        this.drivers = Map.copyOf(Objects.requireNonNull(drivers, "drivers"));
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors, "interceptors"));
        this.eventSink = eventSink;
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
        // Duplicate first: a second load of the same id is the caller's mistake
        // whatever the graph contains, and reporting a structural problem about a
        // graph that is already loaded sends the reader after the wrong thing.
        // This check answers the message; the putIfAbsent below is what decides,
        // so a race cannot double-register.
        if (graphMap.containsKey(id)) {
            throw new IllegalArgumentException("Graph already loaded: " + id);
        }
        // No graph-shape validation here: the graph was checked when it was
        // built (JoinDomains, from the Graph constructor), so an unloadable
        // shape cannot be constructed at all. A second check on this entry
        // point would be a fact that holds only for callers who go through it.
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
    public Optional<Graph> find(String graphId) { return Optional.ofNullable(graphMap.get(graphId)); }

    // --- eval ---

    @Override
    public void run(Graph graph, FlowContext context) throws FlowException {
        Objects.requireNonNull(context, "context");
        // A fresh top-level run owns its context: a reused one must not carry
        // a previous run's stop flag or subscriptions into it.
        FlowContexts.setStopped(context, false);
        FlowExecution execution = new FlowExecution(graph, this, driver(graph), context);
        run(graph, execution);
    }

    @Override
    public void run(Graph graph, FlowExecution execution) throws FlowException {
        if (!execution.isSubgraphExecution()) {
            execution.context().eventBus().clear();
            // The sink is the engine's, the bus is the context's: a reused
            // context takes the sink of the engine running it now.
            execution.context().eventBus().forwardTo(eventSink);
        }
        FlowInterceptor.FlowChain walk = () -> this.walk(execution, graph.start());
        runChain(0, execution.context(), graph, walk);

        // A gateway dead end (EXCLUSIVE with no match/default, or a join that
        // never received all its branches) must not report success: the graph
        // never reached its END node. Stopped runs are exempt — stopping is
        // intentional. The check also covers a no-op chain: an interceptor
        // that never proceeds leaves the graph un-run, which is indistinguish-
        // able from a veto and therefore legal.
        if (!execution.isStopped()) {
            ExecState.DeadEnd deadEnd = execution.execState().deadEnd();
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
            execution.context().eventBus().clear();
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

    private void walk(FlowExecution execution, Node entry) throws FlowException {
        ArrayDeque<Node> frontier = new ArrayDeque<>();
        if (entry != null) {
            frontier.push(entry);
        }
        while (!frontier.isEmpty()) {
            Node node = frontier.pop();
            if (execution.isStopped()) {
                return;
            }
            switch (node.type()) {
                case START -> {
                    if (nodeHooks(execution, node)) fanOut(execution, node, frontier);
                }
                case END -> {
                    if (onNodeStart(execution, node)) {
                        execution.markEnded(node.graph());
                        onNodeEnd(execution, node);
                    }
                }
                case ACTIVITY -> {
                    if (taskExec(execution, node)) fanOut(execution, node, frontier);
                }
                case EXCLUSIVE -> {
                    if (taskExec(execution, node)) exclusiveOut(execution, node, frontier);
                }
                case INCLUSIVE -> {
                    if (joinArrived(execution, node) && taskExec(execution, node)) {
                        fanOut(execution, node, frontier);
                    }
                }
                case PARALLEL -> {
                    if (joinArrived(execution, node) && taskExec(execution, node)) {
                        parallelOut(execution, node);
                    }
                }
                case LOOP -> loopRun(execution, node, frontier);
            }
        }
    }

    /** START/END lifecycle: the hook pair only, no task, no condition. */
    private boolean nodeHooks(FlowExecution execution, Node node) {
        if (!onNodeStart(execution, node)) return false;
        return onNodeEnd(execution, node);
    }

    /** And-fan-out: every link whose condition matches joins the frontier, in link order. */
    private void fanOut(FlowExecution execution, Node node, ArrayDeque<Node> frontier)
            throws FlowException {
        List<Node> matched = new ArrayList<>();
        for (Link link : node.nextLinks()) {
            if (conditionTest(execution, link.when(), true)) {
                matched.add(link.nextNode());
            }
        }
        // Reverse push: the frontier pops in declared link order.
        for (int i = matched.size() - 1; i >= 0; i--) {
            frontier.push(matched.get(i));
        }
    }

    /** Xor-fan-out: first matching link wins; else the default; else dead end. */
    private void exclusiveOut(FlowExecution execution, Node node, ArrayDeque<Node> frontier)
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
            } else if (conditionTest(execution, link.when(), false)) {
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
            markDeadEnd(execution, node);
        }
    }

    /**
     * Join bookkeeping shared by INCLUSIVE and PARALLEL. The transition
     * itself — count, decide, and the provisional dead-end — lives in
     * {@link ExecState#join}, which holds the node monitor; an earlier arrival
     * records a provisional dead end (cleared on activation) so a run that
     * completes without full arrival fails loudly instead of silently.
     */
    private boolean joinArrived(FlowExecution execution, Node node) {
        return execution.execState().join(
            node.graph(), node.id(), node.prevLinks().size());
    }

    private void markDeadEnd(FlowExecution execution, Node node) {
        execution.execState().deadEnd(node.graph(), node.id());
    }

    /** Task lifecycle: hooks around the node's condition-gated data + task, exactly-one-end. */
    private boolean taskExec(FlowExecution execution, Node node) throws FlowException {
        boolean ended = false;
        try {
            // onNodeStart runs inside the try so the pairing below is
            // unconditional: whether it succeeds, returns false (stopped) or
            // throws, onNodeEnd is invoked exactly once — otherwise
            // interceptors and drivers maintaining per-node state (e.g. a
            // nesting stack) leak a start without an end.
            if (!onNodeStart(execution, node)) return false;

            if (conditionTest(execution, node.when(), true)) {
                if (!node.data().isEmpty()) {
                    execution.context().putAll(node.data());
                }
                try {
                    execution.driver().handleTask(execution, node.task());
                } catch (FlowException e) {
                    throw e;
                } catch (IllegalStateException | IllegalArgumentException e) {
                    throw e; // configuration errors — preserve original type
                } catch (Throwable e) {
                    throw new FlowException(
                        FlowException.TASK_FAILED + ": " + node.graph().id() + " / " + node.id(), e);
                }
            }

            if (execution.isStopped()) return false;
            ended = true;
            return onNodeEnd(execution, node);
        } finally {
            if (!ended) {
                try {
                    onNodeEnd(execution, node);
                } catch (Exception ex) {
                    LOG.warn("onNodeEnd failed after task failure at {}/{}",
                        node.graph().id(), node.id(), ex);
                }
            }
        }
    }

    private boolean onNodeStart(FlowExecution execution, Node node) {
        for (FlowInterceptor interceptor : interceptors) {
            interceptor.onNodeStart(execution.context(), node);
        }
        execution.driver().onNodeStart(execution, node);
        return !execution.isStopped();
    }

    private boolean onNodeEnd(FlowExecution execution, Node node) {
        for (FlowInterceptor interceptor : interceptors) {
            interceptor.onNodeEnd(execution.context(), node);
        }
        execution.driver().onNodeEnd(execution, node);
        return !execution.isStopped();
    }

    private boolean conditionTest(FlowExecution execution, ConditionDesc condition, boolean def)
            throws FlowException {
        if (condition.isEmpty()) return def;
        try {
            return execution.driver().handleCondition(execution, condition);
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

    private void parallelOut(FlowExecution execution, Node node) throws FlowException {
        List<Node> branches = node.nextNodes();
        if (execution.driver().executor() == null || branches.size() < 2) {
            // No executor (or nothing to overlap): branches run inline, each
            // still isolated and merged in order.
            for (Node branch : branches) {
                runBranch(execution, node, branch);
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
                execution.driver().executor().execute(() -> {
                    try {
                        if (errorRef.get() != null) return;
                        runBranch(execution, node, branch);
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
     * fails the run with both values named. A branch that <em>failed</em>
     * contributes nothing: the scope closes without committing and its partial
     * writes are discarded with it. A branch that stopped ({@code ctx.stop()})
     * is a legal early end, not a rollback — its writes merged before the stop
     * are kept, exactly as a stopped non-branch walk keeps them.
     */
    private void runBranch(FlowExecution execution, Node fork, Node branch) throws FlowException {
        if ("shared".equals(fork.metadataAsString("join"))) {
            walk(execution, branch);
            return;
        }
        // The scope is the fact: closing without committing discards the
        // buffer on every exit path, so a throwing branch cannot leave a layer
        // behind on this thread. The javadoc's "a stopped or failed branch
        // contributes nothing" is now what the shape does, not what the code
        // around it remembers to do.
        try (FlowContext.Branch scope = execution.context().beginBranch()) {
            walk(execution, branch);
            scope.commit();
        }
    }

    // --- LOOP ---

    private void loopRun(FlowExecution execution, Node node, ArrayDeque<Node> frontier)
            throws FlowException {
        // The predicate has one home — Node#iterates, shared with the graph
        // check — and the name is read once for the binding below. Deciding
        // "does this loop iterate" in two places is how the check and the
        // executor drift apart.
        if (!node.iterates()) {
            // No $in/$for pair: a plain activity gateway (iteration is undefined).
            if (taskExec(execution, node)) fanOut(execution, node, frontier);
            return;
        }
        String forKey = node.metadataAsString("$for");

        ArrayDeque<Iterator<?>> stack = execution.execState().loopStack(node.graph(), node.id());
        Iterator<?> iterator;
        synchronized (stack) {
            // Claim atomically: the "sibling already running this loop" check,
            // the fresh iterator and the push happen under one monitor, so two
            // branches reaching this node cannot both run the body — later
            // arrivals skip the whole node. An exhausted iterator left by a
            // completed run is popped first, re-arming a sequential re-entry.
            if (loopBusy(stack)) return;
            iterator = loopIterator(execution, node);
            stack.push(iterator);
        }

        if (!taskExec(execution, node)) return;

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
            execution.context().put(forKey, iterator.next());
            // A new iteration re-enters the body: join counters (and their
            // provisional dead-ends) left by the previous iteration must not
            // leak into this one.
            resetLoopBodyJoins(execution, node);
            // The body runs to completion per iteration (a nested walk per
            // matched link), not round-robin across iterations.
            List<Node> body = new ArrayList<>();
            for (Link link : node.nextLinks()) {
                if (conditionTest(execution, link.when(), true)) {
                    body.add(link.nextNode());
                }
            }
            for (Node member : body) {
                walk(execution, member);
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
    private Iterator<?> loopIterator(FlowExecution execution, Node node) {
        Object inKey = node.metadata("$in");

        Object inObj;
        if (inKey instanceof List) {
            inObj = inKey;
        } else if (inKey instanceof String inKeyStr) {
            if (!inKeyStr.contains(":") && !inKeyStr.contains("...")) {
                inObj = execution.context().getAs(inKeyStr);
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
    private void resetLoopBodyJoins(FlowExecution execution, Node loopNode) {
        List<String> joins = execution.execState().loopBodyJoins(
            loopNode.graph(), loopNode.id(), () -> loopBodyJoins(loopNode));
        Graph graph = loopNode.graph();
        for (String joinId : joins) {
            execution.execState().joinReset(graph, joinId);
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
