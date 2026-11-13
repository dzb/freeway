package com.jujin.freeway.commons.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * The one Kahn topological sort used by the framework's ordered values:
 * extension contributions and deferred scope actions.
 *
 * <p>Callers own edge construction and domain semantics (which missing
 * references are fatal, how cycles are described, whether unnamed nodes come
 * last). This utility owns the part that must not be written twice: indegree
 * bookkeeping, deterministic ready-node selection and the incomplete result
 * that means a cycle.</p>
 *
 * <p>Every node in {@code outgoing} values must also be a node in
 * {@code nodes}; absent edges are fine. Equal comparator ranks fall back to
 * input order, so a caller never inherits {@link PriorityQueue}'s undefined
 * tie order.</p>
 */
public final class TopologicalOrder {

    private TopologicalOrder() {}

    /**
     * A completed prefix plus the nodes that could not be placed. An empty
     * {@link #remaining()} is the success case; a caller turns a non-empty
     * remainder into its own cycle diagnostic.
     */
    public record Result<T>(List<T> ordered, List<T> remaining) {
        public Result {
            ordered = List.copyOf(ordered);
            remaining = List.copyOf(remaining);
        }

        public boolean complete() {
            return remaining.isEmpty();
        }
    }

    /**
     * Sorts {@code nodes} by the edges in {@code outgoing}, choosing among
     * ready nodes by {@code readyOrder} and then by input position.
     *
     * @throws IllegalArgumentException when a node is duplicated or an edge
     *         names a target outside {@code nodes}
     */
    public static <T> Result<T> sort(
        List<T> nodes,
        Map<T, ? extends Set<T>> outgoing,
        Comparator<? super T> readyOrder
    ) {
        Objects.requireNonNull(nodes, "nodes");
        Objects.requireNonNull(outgoing, "outgoing");
        Objects.requireNonNull(readyOrder, "readyOrder");

        Map<T, Integer> positions = new LinkedHashMap<>();
        Map<T, Integer> indegree = new LinkedHashMap<>();
        for (T node : nodes) {
            if (positions.putIfAbsent(node, positions.size()) != null) {
                throw new IllegalArgumentException("Duplicate node: " + node);
            }
            indegree.put(node, 0);
        }

        for (Map.Entry<T, ? extends Collection<T>> entry : outgoing.entrySet()) {
            T from = entry.getKey();
            if (!indegree.containsKey(from)) {
                throw new IllegalArgumentException("Edge source is not a node: " + from);
            }
            for (T target : entry.getValue()) {
                if (!indegree.containsKey(target)) {
                    throw new IllegalArgumentException("Edge target is not a node: " + target);
                }
                indegree.merge(target, 1, Integer::sum);
            }
        }

        record Ready<T>(T value, int position) {}
        PriorityQueue<Ready<T>> ready = new PriorityQueue<>(
            Comparator.<Ready<T>, T>comparing(Ready::value, readyOrder)
                .thenComparingInt(Ready::position)
        );
        for (T node : nodes) {
            if (indegree.get(node) == 0) {
                ready.add(new Ready<>(node, positions.get(node)));
            }
        }

        List<T> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            T node = ready.remove().value();
            ordered.add(node);
            Set<T> targets = outgoing.get(node);
            if (targets == null) {
                continue;
            }
            for (T target : targets) {
                int degree = indegree.get(target) - 1;
                indegree.put(target, degree);
                if (degree == 0) {
                    ready.add(new Ready<>(target, positions.get(target)));
                }
            }
        }

        if (ordered.size() == nodes.size()) {
            return new Result<>(ordered, List.of());
        }
        List<T> placed = List.copyOf(ordered);
        List<T> remaining = new ArrayList<>(nodes.size() - ordered.size());
        for (T node : nodes) {
            if (!placed.contains(node)) {
                remaining.add(node);
            }
        }
        return new Result<>(ordered, remaining);
    }
}
