package com.jujin.freeway.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintains a reverse index from marker names to {@link TaskComponent}
 * instances, enabling resolution of task handlers by marker intersection.
 *
 * <p>Matching uses {@code containsAll} semantics: a {@code TaskComponent}
 * matches when its marker set contains every marker required by the node.
 * When multiple components match, the one with the most markers wins
 * (most specific). If two have the same count, resolution fails fast.
 *
 * <p>Thread-safety: registration must happen before any resolution.
 * The index is not thread-safe for concurrent register+resolve.
 */
public class FlowMarkerIndex {

    private final Map<String, List<Entry>> markerToEntries = new HashMap<>();

    /**
     * Registers a TaskComponent with its markers.
     */
    public void register(TaskComponent component, Set<String> markers) {
        if (markers.isEmpty()) {
            return;
        }
        // Defensive copy: the caller's set may be mutated after registration,
        // which would desync the reverse index (stale marker lists while the
        // specificity count drifts).
        Set<String> snapshot = new HashSet<>(markers);
        for (String marker : snapshot) {
            markerToEntries.computeIfAbsent(marker, k -> new ArrayList<>())
                .add(new Entry(component, snapshot));
        }
    }

    /**
     * Registers a TaskComponent by scanning its {@code @FlowMarker} annotations.
     */
    public void register(TaskComponent component) {
        Set<String> markers = extractFlowMarkers(component.getClass());
        register(component, markers);
    }

    /**
     * Resolves the best-matching TaskComponent for the given required markers.
     *
     * @param required the marker names required by the node
     * @return the best-matching TaskComponent, or null if none match
     * @throws IllegalArgumentException if multiple components match with the same specificity
     */
    public TaskComponent resolve(Set<String> required) {
        if (required.isEmpty()) {
            return null;
        }

        // Start with all entries matching the first marker
        String first = required.iterator().next();
        List<Entry> candidates = markerToEntries.get(first);
        if (candidates == null) {
            return null;
        }
        Set<Entry> matches = new HashSet<>(candidates);

        // Intersect with remaining markers
        for (String marker : required) {
            List<Entry> bindings = markerToEntries.get(marker);
            if (bindings == null) {
                return null;
            }
            matches.retainAll(bindings);
            if (matches.isEmpty()) {
                return null;
            }
        }

        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.iterator().next().component;
        }

        // Multiple matches — select the most specific (most markers)
        Entry best = null;
        boolean tie = false;
        for (Entry e : matches) {
            if (best == null || e.markers.size() > best.markers.size()) {
                best = e;
                tie = false;
            } else if (e.markers.size() == best.markers.size()) {
                tie = true;
            }
        }
        if (tie) {
            List<String> names = matches.stream()
                .map(e -> e.component.getClass().getSimpleName())
                .toList();
            throw new IllegalArgumentException(
                "Multiple TaskComponents match markers " + required
                    + " with equal specificity: " + names
            );
        }
        return best != null ? best.component : null;
    }

    /**
     * Returns an immutable snapshot of all registered marker names.
     *
     * <p>Snapshot semantics: the returned set is independent of the index —
     * subsequent {@link #register} calls (or mutations of the returned set)
     * do not affect it. Iterating the snapshot is safe even while another
     * thread registers markers.
     *
     * <p>Intended for consistency validation (e.g. checking that every
     * marker referenced by a graph has a registered handler).
     */
    public Set<String> markers() {
        return Set.copyOf(markerToEntries.keySet());
    }

    /**
     * Extracts marker strings from {@code @FlowMarker} annotations on a class.
     */
    public static Set<String> extractFlowMarkers(Class<?> clazz) {
        FlowMarker[] annotations = clazz.getAnnotationsByType(FlowMarker.class);
        if (annotations.length == 0) {
            return Set.of();
        }
        Set<String> markers = new HashSet<>();
        for (FlowMarker m : annotations) {
            String value = m.value().trim();
            if (!value.isEmpty()) {
                markers.add(value);
            }
        }
        return Set.copyOf(markers);
    }

    private record Entry(TaskComponent component, Set<String> markers) {}
}
