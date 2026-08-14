package com.jujin.freeway.commons.scoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manual scope handle, usable with {@link Defer#within(Consumer)}.
 * Call {@link #rollback()} to discard all deferred actions on normal exit
 * (without throwing).
 */
public final class DeferScope {
    private static final Logger LOG = LoggerFactory.getLogger(DeferScope.class);

    private final List<DeferAction> actions = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private boolean rolledBack;

    DeferScope() {}

    void add(DeferAction action) {
        // Fail fast on duplicate ids: detect the constraint violation at
        // registration time instead of only at drain (commit) time, when a
        // late exception used to drop every deferred action.
        if (action.id() != null && !ids.add(action.id())) {
            throw new IllegalStateException(
                "Duplicate deferred action id '" + action.id() + "'"
            );
        }
        actions.add(action);
    }

    /** Discard all deferred actions when the scope exits normally. */
    public void rollback() {
        rolledBack = true;
    }

    // -- called by Defer.within() --

    boolean isRolledBack() {
        return rolledBack;
    }

    void drain() {
        IllegalStateException orderingFailure = null;
        List<Runnable> ordered;
        try {
            ordered = sort(actions);
        } catch (IllegalStateException ex) {
            // Ordering is impossible (circular before/after constraint).
            // Never drop the deferred actions — commit, cache cleanup and
            // lock release must still run even when their relative order
            // cannot be satisfied. Fall back to registration order, log the
            // failure loudly, and rethrow below so callers still see the
            // constraint bug instead of a silent mis-ordering.
            LOG.error(
                "Deferred action ordering failed; running all actions in registration order",
                ex
            );
            orderingFailure = ex;
            ordered = new ArrayList<>(actions.size());
            for (DeferAction action : actions) {
                ordered.add(action.action());
            }
        }
        for (Runnable action : ordered) {
            try {
                action.run();
            } catch (Throwable ex) {
                // Errors included: deferred cleanup must always run to
                // completion — one failing action must not skip the rest.
                LOG.warn("Deferred action failed", ex);
            }
        }
        if (orderingFailure != null) {
            throw orderingFailure;
        }
    }

    void discard() {
        actions.clear();
        ids.clear();
    }

    /**
     * Sorts actions for drain. Result order:
     * <ol>
     *   <li>constrained named actions — topological order by before/after</li>
     *   <li>unconstrained named actions — registration order</li>
     *   <li>unnamed actions — registration order</li>
     * </ol>
     * Named actions therefore run as a group ahead of unnamed ones even when
     * an unnamed action was registered earlier — if strict registration order
     * across named/unnamed matters, keep all actions unnamed or all named.
     */
    static List<Runnable> sort(List<DeferAction> actions) {
        // Collect all ids referenced by before/after constraints
        Set<String> existingIds = new LinkedHashSet<>();
        for (DeferAction a : actions) {
            if (a.id() != null) existingIds.add(a.id());
        }
        Set<String> constrainedIds = new LinkedHashSet<>();
        for (DeferAction a : actions) {
            if (a.id() == null) continue;
            boolean hasRealConstraint = false;
            for (String other : a.before()) {
                if (existingIds.contains(other)) { hasRealConstraint = true; break; }
            }
            if (!hasRealConstraint) {
                for (String other : a.after()) {
                    if (existingIds.contains(other)) { hasRealConstraint = true; break; }
                }
            }
            if (hasRealConstraint) {
                constrainedIds.add(a.id());
                for (String other : a.before()) constrainedIds.add(other);
                for (String other : a.after()) constrainedIds.add(other);
            }
        }

        // Map id → action
        Map<String, DeferAction> byId = new LinkedHashMap<>();
        for (DeferAction a : actions) {
            if (a.id() != null) {
                DeferAction prev = byId.putIfAbsent(a.id(), a);
                if (prev != null) {
                    throw new IllegalStateException(
                        "Duplicate deferred action id '" + a.id() + "'"
                    );
                }
            }
        }

        // Build in-degree map and edges for constrained ids
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (String id : constrainedIds) {
            indegree.put(id, 0);
            edges.put(id, new ArrayList<>());
        }

        for (DeferAction a : actions) {
            if (a.id() == null || !constrainedIds.contains(a.id())) continue;
            for (String other : a.before()) {
                if (!byId.containsKey(other)) continue; // missing → skip
                edges.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(other);
                indegree.merge(other, 1, Integer::sum);
            }
            for (String other : a.after()) {
                if (!byId.containsKey(other)) continue; // missing → skip
                edges.computeIfAbsent(other, k -> new ArrayList<>()).add(a.id());
                indegree.merge(a.id(), 1, Integer::sum);
            }
        }

        // Kahn's algorithm
        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) queue.addLast(e.getKey());
        }

        List<String> orderedIds = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.pollFirst();
            orderedIds.add(id);
            for (String neighbor : edges.getOrDefault(id, List.of())) {
                int deg = indegree.get(neighbor) - 1;
                indegree.put(neighbor, deg);
                if (deg == 0) queue.addLast(neighbor);
            }
        }

        if (orderedIds.size() < indegree.size()) {
            throw new IllegalStateException(
                "Circular dependency detected in Defer action ordering"
            );
        }

        // Build result list
        List<Runnable> result = new ArrayList<>();
        Map<String, DeferAction> remaining = new LinkedHashMap<>(byId);

        // 1. Ordered named actions (participated in constraints)
        for (String id : orderedIds) {
            DeferAction a = remaining.remove(id);
            if (a != null) result.add(a.action());
        }

        // 2. Remaining named actions (no constraints) — registration order
        for (DeferAction a : actions) {
            if (a.id() != null && remaining.containsKey(a.id())) {
                result.add(a.action());
                remaining.remove(a.id());
            }
        }

        // 3. Unnamed actions — registration order
        for (DeferAction a : actions) {
            if (a.id() == null) result.add(a.action());
        }

        return result;
    }
}
