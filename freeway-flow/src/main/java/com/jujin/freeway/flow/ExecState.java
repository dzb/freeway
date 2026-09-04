package com.jujin.freeway.flow;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Per-execution working state of a {@link FlowExchanger}: stacks, counters,
 * and variables used while a flow (or sub-graph call) is running.
 *
 * <p>Shared across {@link FlowExchanger#copy()} boundaries so loop and
 * inclusive-gateway bookkeeping survives sub-graph switches.
 *
 * <p>Freeway-specific — no counterpart in solon-flow (which carries similar
 * state on a `Temporary` class). Reworked here: per-eval counter reset
 * semantics, dead-end marking, and loop-body join caching.
 */
public class ExecState {
    /**
     * A gateway node the execution got stuck at: an EXCLUSIVE node that
     * matched no condition and has no default link, or an INCLUSIVE/PARALLEL
     * join that never received all its incoming branches. Keyed per
     * (graph, node) so a join that later activates can clear only its own
     * entry without losing a dead-end recorded by a sibling branch.
     */
    public record DeadEnd(String graphId, String nodeId) {}

    /** Root-level key prefix for counters that are not graph-scoped. */
    private static final String ROOT = "_ROOT";

    private final Set<DeadEnd> deadEnds = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    /**
     * Heterogeneous by design: each key owns a stack of a caller-chosen
     * element type (loop iterators, branch bookkeeping), so the map stores
     * {@code Stack<Object>} and the typed accessor casts — every key is
     * used with one consistent element type by its owning code path.
     */
    private final Map<String, Stack<Object>> stacks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> loopBodyJoins = new ConcurrentHashMap<>();
    private final Map<String, Object> vars = new ConcurrentHashMap<>();

    /**
     * Cached join-node ids inside a LOOP's body, used by the engine to reset
     * join counters and provisional dead-ends at each loop iteration start.
     * Computed once per (graph, loop node) per evaluation.
     */
    public List<String> loopBodyJoins(String key, Function<String, List<String>> compute) {
        return loopBodyJoins.computeIfAbsent(key, compute);
    }

    // --- dead-end tracking ---

    /**
     * Records that execution could not continue past the given node (see
     * {@link DeadEnd}). Set at the three gateway dead-end points in
     * {@code FlowEngineDefault}; the engine throws a {@code FlowException} at
     * eval completion if any dead-end remains while the run finished without
     * stop/interrupt.
     */
    public void deadEnd(Graph graph, String nodeId) {
        deadEnds.add(new DeadEnd(graph.getId(), nodeId));
    }

    /**
     * Removes a previously recorded dead-end. Join gateways call this when
     * they activate (all incoming branches arrived), since the "dead-end" was
     * only a provisional wait.
     */
    public void deadEndClear(Graph graph, String nodeId) {
        deadEnds.remove(new DeadEnd(graph.getId(), nodeId));
    }

    /**
     * First recorded dead-end of this evaluation, or {@code null} when the
     * execution completed without getting stuck. No reset is needed at eval
     * start: a fresh {@code ExecState} is created per top-level evaluation,
     * and sub-graph evals intentionally share it so a stuck sub-graph
     * propagates to the caller.
     */
    public DeadEnd deadEnd() {
        return deadEnds.isEmpty() ? null : deadEnds.iterator().next();
    }

    /**
     * Clears all recorded dead-ends. Available for callers that reuse an
     * {@code ExecState} across independent evaluations (the engine itself
     * creates a fresh instance per top-level eval).
     */
    public void deadEndReset() {
        deadEnds.clear();
    }

    /**
     * Gets a stack
     */
    @SuppressWarnings("unchecked")
    public <T> Stack<T> stack(Graph graph, String key) {
        return (Stack<T>) stacks.computeIfAbsent(graph.getId() + "/" + key, k -> new Stack<>());
    }

    /**
     * Gets a count
     */
    public int count(Graph graph, String key) {
        return counter(graph.getId() + "/" + key).get();
    }

    /**
     * Sets a count
     */
    public void countSet(Graph graph, String key, int value) {
        counter(graph.getId() + "/" + key).set(value);
    }

    /**
     * Increments a count
     */
    public int countIncr(Graph graph, String key) {
        return counter(graph.getId() + "/" + key).incrementAndGet();
    }

    /** Gets a root-level count (not graph-scoped). */
    public int count(String key) {
        return counter(ROOT + "/" + key).get();
    }

    /** Sets a root-level count (not graph-scoped). */
    public void countSet(String key, int value) {
        counter(ROOT + "/" + key).set(value);
    }

    /** Increments a root-level count (not graph-scoped). */
    public int countIncr(String key) {
        return counter(ROOT + "/" + key).incrementAndGet();
    }

    /** Adds {@code delta} to a root-level count (not graph-scoped). */
    public int countIncr(String key, int delta) {
        return counter(ROOT + "/" + key).addAndGet(delta);
    }

    /**
     * Returns the live execution-variable map. Mutations through this map
     * (e.g. {@code state.vars().put(key, value)}) are visible to the running
     * evaluation — this is the write path for custom drivers and nodes.
     */
    public Map<String, Object> vars() {
        return vars;
    }

    /** Reads an execution variable, cast to the requested type. */
    @SuppressWarnings("unchecked")
    public <T> T varAs(String key) {
        return (T) vars.get(key);
    }

    private AtomicInteger counter(String fullKey) {
        return counts.computeIfAbsent(fullKey, k -> new AtomicInteger(0));
    }

    @Override
    public String toString() {
        return "ExecState{" +
                "counts=" + counts +
                ", stacks=" + stacks +
                '}';
    }
}
