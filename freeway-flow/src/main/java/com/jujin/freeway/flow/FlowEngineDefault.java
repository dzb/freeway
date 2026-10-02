package com.jujin.freeway.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
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
        // Duplicate first: a second load of the same id is the caller's mistake
        // whatever the graph contains, and reporting a structural problem about a
        // graph that is already loaded sends the reader after the wrong thing.
        // This check answers the message; the putIfAbsent below is what decides,
        // so a race cannot double-register.
        if (graphMap.containsKey(id)) {
            throw new IllegalArgumentException("Graph already loaded: " + id);
        }
        // Validated before anything is published: a rejected graph must leave the
        // engine as it found it, and the way to guarantee that is not to register
        // it at all — a rollback would be a second answer to "is this graph
        // loaded", and the retry of a rejected id relies on there being only one.
        rejectUnsatisfiableJoin(graph, id);
        if (graphMap.putIfAbsent(id, graph) != null) {
            throw new IllegalArgumentException("Graph already loaded: " + id);
        }
    }

    /**
     * Refuses a join whose branches cannot all arrive — the one graph shape the
     * arrival accounting has no answer for.
     *
     * <p>A join waits for {@code prevLinks().size()} arrivals. That count is
     * right when every branch reaches the join exactly once, and it stops being
     * right the moment a LOOP sits upstream: a branch inside a loop body arrives
     * once <em>per iteration</em>. Mixed with a branch outside the loop, the
     * expected count matches neither the per-iteration arrivals nor the
     * accumulated ones, so no reset policy satisfies it — zeroing each iteration
     * starves the join of the outside branch, and accumulating over-activates it
     * and then re-marks it dead on the next arrival. Both policies shipped before
     * this check, and both surfaced at the end of the run as
     *
     * <pre>dead end at node 'j' (… a join gateway never received all its incoming branches)</pre>
     *
     * <p>which describes a lost branch. No branch was lost — the graph asked for
     * something the engine cannot count. That is a configuration error, and this
     * framework rejects those at load with the node named, rather than letting
     * them surface as a run-time symptom pointing at the wrong cause.
     * </p>
     *
     * <p>What is fine: every branch arriving once per the same unit — a join with
     * no loop upstream of it, or a join fed entirely from within one iteration
     * domain (see {@link #iterationDomains}), including directly from a loop node
     * itself, since {@link #loopRun} walks the loop's matching successors once per
     * item. That is the shape the per-iteration reset exists for.
     *
     * <p>A LOOP without {@code $for} fans out once, so it counts as no loop at
     * all (see {@link #iterates}).
     */
    private static void rejectUnsatisfiableJoin(Graph graph, String graphId) {
        IterationDomains domains = iterationDomains(graph);
        for (Node node : graph.nodes().values()) {
            if (node.type() != NodeType.INCLUSIVE && node.type() != NodeType.PARALLEL) continue;
            if (node.prevLinks().size() < 2) continue;
            String domain = null;
            boolean first = true;
            boolean mixed = false;
            for (Link link : node.prevLinks()) {
                String branch = domains.outermost().get(link.prevNode().id());
                // `first`, not `domain == null`: null is itself a legitimate
                // domain (a branch above no loop), so using it as the sentinel
                // would let a later branch overwrite it and the comparison would
                // never run.
                if (first) {
                    domain = branch;
                    first = false;
                } else if (!Objects.equals(domain, branch)) {
                    mixed = true;
                }
                // A branch that two loops repeat without either containing the
                // other belongs to neither exclusively, so no single domain can
                // describe it: the arrival count is wrong whichever way it is
                // read, and that is a rejection no matter what the other
                // branches say.
                if (domains.ambiguous().contains(link.prevNode().id())) {
                    mixed = true;
                }
            }
            if (mixed) {
                throw new IllegalArgumentException(
                    "Join '" + node.id() + "' in graph '" + graphId + "' is fed from "
                        + "different iteration domains (" + describeDomains(node, domains)
                        + "): a join waits for " + node.prevLinks().size() + " arrivals, "
                        + "but a branch upstream of a LOOP arrives once per iteration "
                        + "while one outside every LOOP arrives once, so that count can "
                        + "line up with neither one iteration nor the whole run. Give "
                        + "every branch the same domain — all inside one loop, or all "
                        + "downstream of them."
                );
            }
        }
    }

    /** The domains a join's branches come from, named, for the error message. */
    private static String describeDomains(Node join, IterationDomains domains) {
        List<String> seen = new ArrayList<>();
        for (Link link : join.prevLinks()) {
            String id = link.prevNode().id();
            List<String> owning = domains.owners().get(id);
            String label;
            if (owning == null || owning.isEmpty()) {
                label = "'" + id + "' runs once";
            } else if (owning.size() == 1) {
                label = "'" + id + "' repeats per iteration of '" + owning.get(0) + "'";
            } else {
                label = "'" + id + "' repeats per iteration of "
                    + String.join(" and ", owning.stream().map(loop -> "'" + loop + "'").toList());
            }
            if (!seen.contains(label)) {
                seen.add(label);
            }
        }
        return String.join(", ", seen);
    }

    /**
     * Which iterating loop repeats each node a join branches from, and where no
     * single loop can claim one.
     *
     * <p>Outermost is the granularity that matters, because the per-iteration
     * join reset is re-armed by the outermost loop's body: a join fed by a nested
     * loop's node and by a sibling inside the same outer body gets exactly one
     * arrival from each per outer iteration, which is countable. Innermost would
     * call that pair "different domains" and refuse a graph the engine runs.
     *
     * <p>Ownership is never awarded to the first loop examined: that depends on
     * the order the loops were declared in (same-depth siblings are otherwise
     * unordered), and the verdict has to be a property of the graph, not of the
     * document that declares it. Instead each branch node is asked which loops
     * can reach it, walking backwards from the node; a node two loops repeat
     * where neither contains the other has no outermost owner at all and is
     * reported as ambiguous rather than awarded to whichever loop came first.
     *
     * <p>Only join branches are asked, not every node: the walk is over the
     * branch's ancestors, so the cost is bounded by the shape that is actually
     * being validated rather than by loops x downstream. The straightforward
     * "every loop walks everything it reaches" version measured 1 651 ms on 500
     * sibling loops over a 20 000-node shared tail; this way is 106 ms on that
     * graph, 38 ms on the 5 000-node one, and flat in the number of loops.
     */
    private static IterationDomains iterationDomains(Graph graph) {
        Set<String> branches = new HashSet<>();
        for (Node node : graph.nodes().values()) {
            if (node.type() != NodeType.INCLUSIVE && node.type() != NodeType.PARALLEL) continue;
            if (node.prevLinks().size() < 2) continue;
            for (Link link : node.prevLinks()) {
                branches.add(link.prevNode().id());
            }
        }

        Map<String, List<String>> owners = new HashMap<>();
        for (String branch : branches) {
            List<Node> loops = loopsReaching(graph, branch);
            if (loops.isEmpty()) continue;
            // Deterministic, and deliberately not declaration order: the loop id
            // is what the message names, so ties cannot reach the verdict.
            loops.sort(Comparator.comparing(Node::id));
            owners.put(branch, loops.stream().map(Node::id).toList());
        }

        Map<String, Integer> depth = nestingDepth(graph);
        Map<String, String> outermost = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : owners.entrySet()) {
            List<String> owning = entry.getValue();
            String outer = owning.get(0);
            for (String candidate : owning) {
                if (depth.getOrDefault(candidate, 0) < depth.getOrDefault(outer, 0)) {
                    outer = candidate;
                }
            }
            outermost.put(entry.getKey(), outer);
            for (String other : owning) {
                if (other.equals(outer) || ambiguous.contains(entry.getKey())) {
                    continue;
                }
                // Nested either way is one domain: the outer loop repeats the
                // inner one, so the node has a single outermost owner after all.
                if (!reachesLoop(graph, outer, other) && !reachesLoop(graph, other, outer)) {
                    ambiguous.add(entry.getKey());
                }
            }
        }
        return new IterationDomains(outermost, ambiguous, owners);
    }

    /** Whether {@code ancestor}'s body contains the loop {@code nodeId}. */
    private static boolean reachesLoop(Graph graph, String ancestor, String nodeId) {
        return loopsReaching(graph, nodeId).stream()
            .anyMatch(loop -> loop.id().equals(ancestor));
    }

    /**
     * The iterating loops that can reach {@code nodeId} — its own Loop ancestors,
     * found by walking links backwards. The node counts as its own owner when it
     * <em>is</em> a loop, since {@code loopRun} walks a loop's successors once per
     * item and a join that branches straight off the loop node is inside it.
     */
    private static List<Node> loopsReaching(Graph graph, String nodeId) {
        List<Node> loops = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<Node> pending = new ArrayDeque<>();
        Node start = graph.nodes().get(nodeId);
        if (start != null) {
            pending.push(start);
        }
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node.id())) {
                continue;
            }
            if (node.type() == NodeType.LOOP && iterates(node)) {
                loops.add(node);
            }
            for (Link link : node.prevLinks()) {
                pending.push(link.prevNode());
            }
        }
        return loops;
    }

    /** Which loop repeats each node, and where that is not a single answer. */
    private record IterationDomains(
        Map<String, String> outermost,
        Set<String> ambiguous,
        Map<String, List<String>> owners
    ) {
    }


    /**
     * Iterating loops above each node, memoized over the whole graph.
     *
     * <p>Built from {@code nextLinks} rather than {@link Node#prevLinks()},
     * which lazily scans every link in the graph on first touch per node — asked
     * of every node in turn that is O(V x E).
     */
    private static Map<String, Integer> nestingDepth(Graph graph) {
        Map<String, List<Node>> parents = new HashMap<>();
        for (Node node : graph.nodes().values()) {
            for (Link link : node.nextLinks()) {
                parents.computeIfAbsent(link.nextId(), key -> new ArrayList<>()).add(node);
            }
        }
        Map<String, Integer> memo = new HashMap<>();
        for (Node node : graph.nodes().values()) {
            depthAbove(node, parents, memo);
        }
        return memo;
    }

    /** Graphs are DAGs, so the walk up terminates; the memo makes it one pass. */
    private static int depthAbove(Node node, Map<String, List<Node>> parents,
                                  Map<String, Integer> memo) {
        Integer known = memo.get(node.id());
        if (known != null) {
            return known;
        }
        int deepest = 0;
        for (Node parent : parents.getOrDefault(node.id(), List.of())) {
            deepest = Math.max(deepest, depthAbove(parent, parents, memo));
        }
        int depth = deepest + (node.type() == NodeType.LOOP && iterates(node) ? 1 : 0);
        memo.put(node.id(), depth);
        return depth;
    }

    /**
     * Whether this LOOP iterates. One definition, shared with {@link #loopRun}:
     * a LOOP without {@code $for} is a plain activity gateway that fans out once,
     * so its successors do not arrive per iteration and nothing about them is
     * loop bookkeeping. Deciding that twice — once where the engine runs, once
     * where the graph is checked — is how the two drift apart, and the check then
     * refuses a graph the engine runs perfectly well.
     */
    private static boolean iterates(Node loop) {
        return loop.metaAsString("$for") != null;
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
        FlowEvaluation evaluation = new FlowEvaluation(graph, this, driver(graph), context);
        eval(graph, evaluation);
    }

    @Override
    public void eval(Graph graph, FlowEvaluation evaluation) throws FlowException {
        if (!evaluation.isSubgraphEval()) {
            evaluation.context().eventBus().clear();
        }
        FlowInterceptor.FlowChain walk = () -> this.walk(evaluation, graph.start());
        runChain(0, evaluation.context(), graph, walk);

        // A gateway dead end (EXCLUSIVE with no match/default, or a join that
        // never received all its branches) must not report success: the graph
        // never reached its END node. Stopped runs are exempt — stopping is
        // intentional. The check also covers a no-op chain: an interceptor
        // that never proceeds leaves the graph un-run, which is indistinguish-
        // able from a veto and therefore legal.
        if (!evaluation.isStopped()) {
            ExecState.DeadEnd deadEnd = evaluation.execState().deadEnd();
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
            evaluation.context().eventBus().clear();
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

    private void walk(FlowEvaluation evaluation, Node entry) throws FlowException {
        ArrayDeque<Node> frontier = new ArrayDeque<>();
        if (entry != null) {
            frontier.push(entry);
        }
        while (!frontier.isEmpty()) {
            Node node = frontier.pop();
            if (evaluation.isStopped()) {
                return;
            }
            switch (node.type()) {
                case START -> {
                    if (nodeHooks(evaluation, node)) fanOut(evaluation, node, frontier);
                }
                case END -> {
                    if (onNodeStart(evaluation, node)) {
                        evaluation.markEnded(node.graph());
                        onNodeEnd(evaluation, node);
                    }
                }
                case ACTIVITY -> {
                    if (taskExec(evaluation, node)) fanOut(evaluation, node, frontier);
                }
                case EXCLUSIVE -> {
                    if (taskExec(evaluation, node)) exclusiveOut(evaluation, node, frontier);
                }
                case INCLUSIVE -> {
                    if (joinArrived(evaluation, node) && taskExec(evaluation, node)) {
                        fanOut(evaluation, node, frontier);
                    }
                }
                case PARALLEL -> {
                    if (joinArrived(evaluation, node) && taskExec(evaluation, node)) {
                        parallelOut(evaluation, node);
                    }
                }
                case LOOP -> loopRun(evaluation, node, frontier);
            }
        }
    }

    /** START/END lifecycle: the hook pair only, no task, no condition. */
    private boolean nodeHooks(FlowEvaluation evaluation, Node node) {
        if (!onNodeStart(evaluation, node)) return false;
        return onNodeEnd(evaluation, node);
    }

    /** And-fan-out: every link whose condition matches joins the frontier, in link order. */
    private void fanOut(FlowEvaluation evaluation, Node node, ArrayDeque<Node> frontier)
            throws FlowException {
        List<Node> matched = new ArrayList<>();
        for (Link link : node.nextLinks()) {
            if (conditionTest(evaluation, link.when(), true)) {
                matched.add(link.nextNode());
            }
        }
        // Reverse push: the frontier pops in declared link order.
        for (int i = matched.size() - 1; i >= 0; i--) {
            frontier.push(matched.get(i));
        }
    }

    /** Xor-fan-out: first matching link wins; else the default; else dead end. */
    private void exclusiveOut(FlowEvaluation evaluation, Node node, ArrayDeque<Node> frontier)
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
            } else if (conditionTest(evaluation, link.when(), false)) {
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
            markDeadEnd(evaluation, node);
        }
    }

    /**
     * Join bookkeeping shared by INCLUSIVE and PARALLEL. The transition
     * itself — count, decide, and the provisional dead-end — lives in
     * {@link ExecState#join}, which holds the node monitor; an earlier arrival
     * records a provisional dead end (cleared on activation) so a run that
     * completes without full arrival fails loudly instead of silently.
     */
    private boolean joinArrived(FlowEvaluation evaluation, Node node) {
        return evaluation.execState().join(
            node.graph(), node.id(), node.prevLinks().size());
    }

    private void markDeadEnd(FlowEvaluation evaluation, Node node) {
        evaluation.execState().deadEnd(node.graph(), node.id());
    }

    /** Task lifecycle: hooks around the node's condition-gated data + task, exactly-one-end. */
    private boolean taskExec(FlowEvaluation evaluation, Node node) throws FlowException {
        boolean ended = false;
        try {
            // onNodeStart runs inside the try so the pairing below is
            // unconditional: whether it succeeds, returns false (stopped) or
            // throws, onNodeEnd is invoked exactly once — otherwise
            // interceptors and drivers maintaining per-node state (e.g. a
            // nesting stack) leak a start without an end.
            if (!onNodeStart(evaluation, node)) return false;

            if (conditionTest(evaluation, node.when(), true)) {
                if (!node.data().isEmpty()) {
                    evaluation.context().putAll(node.data());
                }
                try {
                    evaluation.driver().handleTask(evaluation, node.task());
                } catch (FlowException e) {
                    throw e;
                } catch (IllegalStateException | IllegalArgumentException e) {
                    throw e; // configuration errors — preserve original type
                } catch (Throwable e) {
                    throw new FlowException(
                        FlowException.TASK_FAILED + ": " + node.graph().id() + " / " + node.id(), e);
                }
            }

            if (evaluation.isStopped()) return false;
            ended = true;
            return onNodeEnd(evaluation, node);
        } finally {
            if (!ended) {
                try {
                    onNodeEnd(evaluation, node);
                } catch (Exception ex) {
                    LOG.warn("onNodeEnd failed after task failure at {}/{}",
                        node.graph().id(), node.id(), ex);
                }
            }
        }
    }

    private boolean onNodeStart(FlowEvaluation evaluation, Node node) {
        for (FlowInterceptor interceptor : interceptors) {
            interceptor.onNodeStart(evaluation.context(), node);
        }
        evaluation.driver().onNodeStart(evaluation, node);
        return !evaluation.isStopped();
    }

    private boolean onNodeEnd(FlowEvaluation evaluation, Node node) {
        for (FlowInterceptor interceptor : interceptors) {
            interceptor.onNodeEnd(evaluation.context(), node);
        }
        evaluation.driver().onNodeEnd(evaluation, node);
        return !evaluation.isStopped();
    }

    private boolean conditionTest(FlowEvaluation evaluation, ConditionDesc condition, boolean def)
            throws FlowException {
        if (condition.isEmpty()) return def;
        try {
            return evaluation.driver().handleCondition(evaluation, condition);
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

    private void parallelOut(FlowEvaluation evaluation, Node node) throws FlowException {
        List<Node> branches = node.nextNodes();
        if (evaluation.driver().executor() == null || branches.size() < 2) {
            // No executor (or nothing to overlap): branches run inline, each
            // still isolated and merged in order.
            for (Node branch : branches) {
                runBranch(evaluation, node, branch);
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
                evaluation.driver().executor().execute(() -> {
                    try {
                        if (errorRef.get() != null) return;
                        runBranch(evaluation, node, branch);
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
    private void runBranch(FlowEvaluation evaluation, Node fork, Node branch) throws FlowException {
        if ("shared".equals(fork.metaAsString("join"))) {
            walk(evaluation, branch);
            return;
        }
        Runnable merge = evaluation.context().beginBranch();
        // A throwing walk never reaches the merger: an aborted branch
        // contributes nothing, its partial writes discarded with it.
        walk(evaluation, branch);
        merge.run();
    }

    // --- LOOP ---

    private void loopRun(FlowEvaluation evaluation, Node node, ArrayDeque<Node> frontier)
            throws FlowException {
        String forKey = node.metaAsString("$for");
        // forKey is read once and both uses come from it: the guard is
        // "was there one", and the binding below needs the name itself. iterates()
        // is the shared PREDICATE (the graph check uses it too) — two reads of
        // the same meta would be one more place for the two to drift.
        if (forKey == null) {
            // No $in/$for pair: a plain activity gateway (iteration is undefined).
            if (taskExec(evaluation, node)) fanOut(evaluation, node, frontier);
            return;
        }

        ArrayDeque<Iterator<?>> stack = evaluation.execState().loopStack(node.graph(), node.id());
        Iterator<?> iterator;
        synchronized (stack) {
            // Claim atomically: the "sibling already running this loop" check,
            // the fresh iterator and the push happen under one monitor, so two
            // branches reaching this node cannot both run the body — later
            // arrivals skip the whole node. An exhausted iterator left by a
            // completed run is popped first, re-arming a sequential re-entry.
            if (loopBusy(stack)) return;
            iterator = loopIterator(evaluation, node);
            stack.push(iterator);
        }

        if (!taskExec(evaluation, node)) return;

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
            evaluation.context().put(forKey, iterator.next());
            // A new iteration re-enters the body: join counters (and their
            // provisional dead-ends) left by the previous iteration must not
            // leak into this one.
            resetLoopBodyJoins(evaluation, node);
            // The body runs to completion per iteration (a nested walk per
            // matched link), not round-robin across iterations.
            List<Node> body = new ArrayList<>();
            for (Link link : node.nextLinks()) {
                if (conditionTest(evaluation, link.when(), true)) {
                    body.add(link.nextNode());
                }
            }
            for (Node member : body) {
                walk(evaluation, member);
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
    private Iterator<?> loopIterator(FlowEvaluation evaluation, Node node) {
        Object inKey = node.meta("$in");

        Object inObj;
        if (inKey instanceof List) {
            inObj = inKey;
        } else if (inKey instanceof String inKeyStr) {
            if (!inKeyStr.contains(":") && !inKeyStr.contains("...")) {
                inObj = evaluation.context().getAs(inKeyStr);
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
    private void resetLoopBodyJoins(FlowEvaluation evaluation, Node loopNode) {
        List<String> joins = evaluation.execState().loopBodyJoins(
            loopNode.graph(), loopNode.id(), () -> loopBodyJoins(loopNode));
        Graph graph = loopNode.graph();
        for (String joinId : joins) {
            evaluation.execState().joinReset(graph, joinId);
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
