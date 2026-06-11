package com.jujin.freeway.commons.defer;

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
    private boolean rolledBack;

    DeferScope() {}

    void add(DeferAction action) {
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
        List<Runnable> ordered = sort(actions);
        for (Runnable action : ordered) {
            try {
                action.run();
            } catch (Exception ex) {
                LOG.warn("Deferred action failed", ex);
            }
        }
    }

    void discard() {
        actions.clear();
    }

    /**
     * Topologically sorts actions by before/after constraints.
     * Unnamed actions and named actions without constraints float
     * freely (no edges) and run in registration order where possible.
     */
    static List<Runnable> sort(List<DeferAction> actions) {
        // Collect all ids referenced by before/after constraints
        Set<String> constrainedIds = new LinkedHashSet<>();
        for (DeferAction a : actions) {
            if (a.id() != null && (!a.before().isEmpty() || !a.after().isEmpty())) {
                constrainedIds.add(a.id());
            }
        }
        for (DeferAction a : actions) {
            if (a.id() != null) {
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
