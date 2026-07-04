package com.jujin.freeway.ioc.extension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Aggregates contributed values of a given entry type and provides ordered
 * access to them. Each extension point is identified by the entry class
 * itself.
 *
 * <p>Contributions are added via {@link Contributions} during module binding
 * and retrieved at runtime through {@code container.extension(EntryType.class)}:
 * <pre>{@code
 * // Contribute
 * binder.contribute(Route.class).add(Route.get("/", handler));
 *
 * // Consume
 * List<Route> routes = container.extension(Route.class).all();
 * }</pre>
 *
 * @param <V> the entry type (extension point type)
 */
public final class Extension<V> {

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private final Class<V> entryType;
    private volatile List<V> sorted;

    public Extension(Class<V> entryType) {
        this.entryType = Objects.requireNonNull(entryType, "entryType");
    }

    /**
     * Adds a named contribution with ordering support.
     *
     * @param id    unique id for ordering via {@link Contribution#before}/{@link Contribution#after}
     * @param value the contribution value
     * @return a {@link Contribution} handle for declaring ordering constraints
     * @throws IllegalStateException if the id is a duplicate
     */
    public Contribution add(String id, V value) {
        Objects.requireNonNull(value, "value");
        String normalizedId = normalizeOptionalId(id);
        if (normalizedId != null && !ids.add(normalizedId)) {
            throw new IllegalStateException(
                "Duplicate contribution id " +
                    normalizedId +
                    " for extension " +
                    entryType.getSimpleName()
            );
        }
        Entry entry = new Entry(normalizedId, value);
        entries.add(entry);
        sorted = null;
        return entry;
    }

    /**
     * Creates an extension pre-populated with values (no ordering).
     *
     * @param entryType the entry type
     * @param values    the values to include
     * @param <V>       the entry type
     * @return a new Extension containing the given values
     */
    @SafeVarargs
    public static <V> Extension<V> of(Class<V> entryType, V... values) {
        Extension<V> ext = new Extension<>(entryType);
        for (V value : values) ext.add(null, value);
        return ext;
    }

    /**
     * Returns the contribution with the given id, or empty.
     *
     * @param id the contribution id
     * @return the contributed value, or empty if not found
     */
    public Optional<V> get(String id) {
        String normalized = normalizeOptionalId(id);
        if (normalized == null) return Optional.empty();
        for (Entry e : entries) {
            if (normalized.equals(e.id)) return Optional.of(e.value);
        }
        return Optional.empty();
    }

    /**
     * Returns contributions as an id→value map. Unnamed entries
     * (id=null) are excluded. Maintains insertion order.
     *
     * @return an ordered map of named contributions
     */
    public Map<String, V> asMap() {
        Map<String, V> result = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.id != null) result.put(e.id, e.value);
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Returns all contributions in insertion order (or topological order
     * when {@code before/after} constraints are used). The result is cached
     * and invalidated when new contributions are added.
     *
     * @return an unmodifiable list of contributed values
     */
    public List<V> all() {
        if (sorted == null) {
            sorted = order();
        }
        return sorted;
    }

    @Override
    public String toString() {
        return "Extension[" + entryType.getSimpleName() + "]";
    }

    private List<V> order() {
        if (entries.isEmpty()) {
            return List.of();
        }
        if (
            entries
                .stream()
                .allMatch(e -> e.afterIds.isEmpty() && e.beforeIds.isEmpty())
        ) {
            List<V> values = new ArrayList<>(entries.size());
            for (Entry e : entries) values.add(e.value);
            return List.copyOf(values);
        }

        Map<String, Entry> byId = new LinkedHashMap<>();
        Map<Entry, Integer> positions = new LinkedHashMap<>();
        int index = 0;
        for (Entry entry : entries) {
            positions.put(entry, index++);
            if (entry.id != null) {
                byId.put(entry.id, entry);
            }
        }

        Map<Entry, Set<Entry>> outgoing = new LinkedHashMap<>();
        Map<Entry, Integer> indegree = new LinkedHashMap<>();
        for (Entry entry : entries) {
            outgoing.put(entry, new LinkedHashSet<>());
            indegree.put(entry, 0);
        }

        for (Entry entry : entries) {
            for (String id : entry.afterIds) {
                Entry dep = byId.get(id);
                if (dep == null) {
                    throw new IllegalArgumentException(
                        "Unknown id '" + id + "' in after() — referenced by '"
                            + entry.id + "'");
                }
                addEdge(dep, entry, outgoing, indegree);
            }
            for (String id : entry.beforeIds) {
                Entry target = byId.get(id);
                if (target == null) {
                    throw new IllegalArgumentException(
                        "Unknown id '" + id + "' in before() — referenced by '"
                            + entry.id + "'");
                }
                addEdge(entry, target, outgoing, indegree);
            }
        }

        List<Entry> ordered = new ArrayList<>(entries.size());
        PriorityQueue<Entry> ready = new PriorityQueue<>(
            Comparator.comparingInt(positions::get)
        );
        for (Entry entry : entries) {
            if (indegree.get(entry) == 0) ready.add(entry);
        }

        while (!ready.isEmpty()) {
            Entry entry = ready.remove();
            ordered.add(entry);
            for (Entry target : outgoing.get(entry)) {
                int deg = indegree.get(target) - 1;
                indegree.put(target, deg);
                if (deg == 0) ready.add(target);
            }
        }

        if (ordered.size() != entries.size()) {
            throw new IllegalStateException(
                "Contribution order cycle detected for extension " + entryType.getSimpleName()
            );
        }
        List<V> values = new ArrayList<>(ordered.size());
        for (Entry e : ordered) values.add(e.value);
        return List.copyOf(values);
    }

    private void addEdge(
        Entry from,
        Entry to,
        Map<Entry, Set<Entry>> outgoing,
        Map<Entry, Integer> indegree
    ) {
        if (from == to) return;
        if (outgoing.get(from).add(to)) indegree.put(to, indegree.get(to) + 1);
    }

    private static String normalizeOptionalId(String id) {
        if (id == null) return null;
        String v = id.trim();
        if (v.isEmpty()) throw new IllegalArgumentException(
            "Contribution id must not be blank"
        );
        return v;
    }

    private final class Entry implements Contribution {

        final String id;
        final V value;
        final List<String> beforeIds = new ArrayList<>();
        final List<String> afterIds = new ArrayList<>();

        Entry(String id, V value) {
            this.id = id;
            this.value = value;
        }

        @Override
        public Contribution before(String... ids) {
            for (String s : ids)
                beforeIds.add(Objects.requireNonNull(s, "id").trim());
            return this;
        }

        @Override
        public Contribution after(String... ids) {
            for (String s : ids)
                afterIds.add(Objects.requireNonNull(s, "id").trim());
            return this;
        }
    }
}
