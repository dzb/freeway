package com.jujin.freeway.flow;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Per-evaluation working state of a {@link FlowEvaluation}: join counters,
 * loop iterators and dead-end marks. A fresh instance belongs to one
 * top-level evaluation; sub-graph calls share it so a stuck sub-graph
 * propagates to its caller.
 *
 * <p>Everything here is keyed by (graph, node) and owned by the engine:
 * custom drivers and handlers observe the flow through
 * {@link FlowContext}, not by writing into this state — there is no
 * shared string-keyed bag for a key collision to corrupt.</p>
 */
public final class ExecState {
    /**
     * A gateway node the execution got stuck at: an EXCLUSIVE node that
     * matched no condition and has no default link, or an INCLUSIVE/PARALLEL
     * join that never received all its incoming branches. Keyed per
     * (graph, node) so a join that later activates can clear only its own
     * entry without losing a dead-end recorded by a sibling branch.
     */
    public record DeadEnd(String graphId, String nodeId) {}

    private final Set<DeadEnd> deadEnds = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
    private final Map<String, ArrayDeque<Iterator<?>>> loopStacks = new ConcurrentHashMap<>();
    private final Map<String, List<String>> loopBodyJoins = new ConcurrentHashMap<>();

    // --- dead-end tracking ---

    /**
     * Records that execution could not continue past the given node (see
     * {@link DeadEnd}). The engine throws a {@code FlowException} at eval
     * completion if a dead-end remains while the run finished without stop.
     */
    public void deadEnd(Graph graph, String nodeId) {
        deadEnds.add(new DeadEnd(graph.id(), nodeId));
    }

    /**
     * Removes a previously recorded dead-end. Join gateways call this when
     * they activate (all incoming branches arrived), since the "dead-end"
     * was only a provisional wait.
     */
    public void deadEndClear(Graph graph, String nodeId) {
        deadEnds.remove(new DeadEnd(graph.id(), nodeId));
    }

    /**
     * First recorded dead-end of this evaluation, or {@code null} when the
     * execution completed without getting stuck. No reset is needed at eval
     * start: a fresh instance is created per top-level evaluation.
     */
    public DeadEnd deadEnd() {
        return deadEnds.isEmpty() ? null : deadEnds.iterator().next();
    }

    // --- join counters ---

    int countIncr(Graph graph, String nodeId) {
        return counter(graph.id() + "/" + nodeId).incrementAndGet();
    }

    void countSet(Graph graph, String nodeId, int value) {
        counter(graph.id() + "/" + nodeId).set(value);
    }

    private AtomicInteger counter(String fullKey) {
        return counts.computeIfAbsent(fullKey, k -> new AtomicInteger(0));
    }

    // --- loop bookkeeping ---

    /**
     * The iterator stack of one {@code $for} LOOP node, per this evaluation.
     * The deque is the monitor for claim/peek — two branches reaching the
     * same loop node cannot both run it; the engine synchronizes on it.
     */
    ArrayDeque<Iterator<?>> loopStack(Graph graph, String nodeId) {
        return loopStacks.computeIfAbsent(graph.id() + "/" + nodeId,
            k -> new ArrayDeque<>());
    }

    /**
     * Cached join-node ids inside a LOOP's body, used by the engine to reset
     * join counters at each iteration start. Computed once per
     * (graph, loop node) per evaluation.
     */
    List<String> loopBodyJoins(Graph graph, String nodeId,
            Supplier<List<String>> compute) {
        return loopBodyJoins.computeIfAbsent(graph.id() + "/" + nodeId,
            k -> compute.get());
    }
}
