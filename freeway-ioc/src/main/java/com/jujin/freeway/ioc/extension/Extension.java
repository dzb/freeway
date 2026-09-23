package com.jujin.freeway.ioc.extension;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Aggregates contributed values of a given entry type and provides ordered
 * access to them. Each extension point is identified by the entry class
 * itself.
 *
 * <p>Contributions are added via {@link Contribution} during module binding
 * and retrieved at runtime through {@code container.extension(EntryType.class)}:
 * <pre>{@code
 * // Contribute
 * binder.contribute(Route.class).add(Route.get("/", handler));
 *
 * // Consume
 * List<Route> routes = container.extension(Route.class).all();
 * }</pre>
 *
 * <p>The container {@link #seal() seals} every extension once composition
 * finishes: {@link #add} and {@link Ordering#before}/{@link Ordering#after}
 * are accepted only while modules are binding, never after the container is
 * built. Readers are lock-free — the data is immutable from the seal on, and
 * the composition thread's writes are published with it.
 *
 * @param <T> the entry type (extension point type)
 */
public final class Extension<T> {
    private static final Logger LOG = LoggerFactory.getLogger(Extension.class);

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private final Class<T> entryType;
    private volatile List<T> sorted;
    private volatile Map<String, T> mapCache;
    /** Set by the container after composition; visible to every later reader. */
    private volatile boolean sealed;

    public Extension(Class<T> entryType) {
        this.entryType = Objects.requireNonNull(entryType, "entryType");
    }

    /**
     * Closes the contribution window: after the seal, {@link #add} and
     * {@link Ordering#before}/{@link Ordering#after} throw. Idempotent —
     * the container seals every extension (including ones created later
     * through a lazy lookup) exactly once per composition.
     */
    public void seal() {
        sealed = true;
    }

    /**
     * Adds a named contribution with ordering support.
     *
     * @param id    unique id for ordering via {@link Ordering#before}/{@link Ordering#after}
     * @param value the contribution value
     * @return an {@link Ordering} handle for declaring ordering constraints
     * @throws IllegalStateException if the id is a duplicate, or if this
     *         extension is already sealed
     */
    public Ordering add(String id, T value) {
        Objects.requireNonNull(value, "value");
        requireMutable("add(id, value)");
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
        mapCache = null;
        return entry;
    }

    /**
     * Returns contributions as an id→value map. Unnamed entries
     * (id=null) are excluded. Maintains insertion order.
     *
     * <p>The returned map is an immutable point-in-time snapshot: later
     * contributions are visible only through a fresh call.
     *
     * @return an ordered map of named contributions
     */
    public Map<String, T> asMap() {
        Map<String, T> cached = mapCache;
        if (cached != null) {
            return cached;
        }
        Map<String, T> result = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.id != null) result.put(e.id, e.value);
        }
        // Unmodifiable *view* (not Map.copyOf): asMap() promises
        // insertion order, which copyOf does not guarantee.
        cached = Collections.unmodifiableMap(result);
        mapCache = cached;
        return cached;
    }

    /**
     * Returns all contributions in insertion order (or topological order
     * when {@code before/after} constraints are used). The result is cached
     * and invalidated when new contributions are added.
     *
     * @return an unmodifiable list of contributed values
     */
    public List<T> all() {
        List<T> s = sorted;
        if (s != null) {
            return s;
        }
        s = order();
        sorted = s;
        return s;
    }

    @Override
    public String toString() {
        return "Extension[" + entryType.getSimpleName() + "]";
    }

    /**
     * Fails fast when any {@code before/after} ordering reference points to
     * an unknown contribution id. The generic {@link #all()} ordering stays
     * lenient — unknown references are WARNed and ignored — because a
     * missing sibling is harmless for most extension points. Strict
     * consumers (e.g. runtime-hook ordering in the boot layer) call this
     * before resolving so a typo like {@code after("freeway.http.serve")}
     * fails startup instead of silently running hooks in the wrong order.
     *
     * @throws IllegalStateException naming the missing id, the ordering
     *         method, and the contribution that declared the reference
     */
    public void validateOrdering() {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.id != null) {
                byId.put(entry.id, entry);
            }
        }
        for (Entry entry : entries) {
            for (String id : entry.afterIds) {
                if (!byId.containsKey(id)) {
                    throw missingReference(id, "after()", entry);
                }
            }
            for (String id : entry.beforeIds) {
                if (!byId.containsKey(id)) {
                    throw missingReference(id, "before()", entry);
                }
            }
        }
    }

    /**
     * Guards every mutation. Writes happen on the composition thread only
     * (module bind, then the deferred drain) — no lock is needed — and stop
     * entirely at the seal, which is what makes the read side lock-free.
     */
    void requireMutable(String op) {
        if (sealed) {
            throw new IllegalStateException(
                "Extension " + entryType.getSimpleName() + " is sealed — " +
                    op + " is accepted only during composition (module bind), " +
                    "before the container is built"
            );
        }
    }

    private IllegalStateException missingReference(
        String id,
        String method,
        Entry entry
    ) {
        String owner = entry.id != null
            ? "contribution '" + entry.id + "'"
            : "contribution <" + entry.value.getClass().getName() + ">";
        return new IllegalStateException(
            "Ordering reference to unknown id '" + id + "' in " + method
                + " of " + owner + " for extension "
                + entryType.getSimpleName()
                + " — check for a typo, or a module that is not installed"
        );
    }

    private List<T> order() {
        if (entries.isEmpty()) {
            return List.of();
        }
        if (
            entries
                .stream()
                .allMatch(e -> e.afterIds.isEmpty() && e.beforeIds.isEmpty())
        ) {
            List<T> values = new ArrayList<>(entries.size());
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
                    warnMissing(id, "after()", entry);
                    continue;
                }
                addEdge(dep, entry, outgoing, indegree);
            }
            for (String id : entry.beforeIds) {
                Entry target = byId.get(id);
                if (target == null) {
                    warnMissing(id, "before()", entry);
                    continue;
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
            // Kahn stalled, so the unprocessed subgraph contains at least one
            // cycle — name its members, or the error only says which
            // extension point to stare at.
            Set<Entry> placed = new HashSet<>(ordered);
            List<Entry> remaining = new ArrayList<>();
            for (Entry e : entries) {
                if (!placed.contains(e)) remaining.add(e);
            }
            throw new IllegalStateException(
                "Contribution order cycle detected for extension "
                    + entryType.getSimpleName()
                    + ": "
                    + describeCycle(findCycle(outgoing, remaining))
            );
        }
        List<T> values = new ArrayList<>(ordered.size());
        for (Entry e : ordered) values.add(e.value);
        return List.copyOf(values);
    }

    /**
     * The cycle among {@code remaining}: strip dead ends (no edge back into
     * the set — they cannot lie on a cycle), then walk out-edges until a
     * node repeats; that repeat closes the cycle. Instance method because
     * {@code Entry} captures {@code T}, which a static context cannot reference.
     */
    private List<Entry> findCycle(
        Map<Entry, Set<Entry>> outgoing,
        List<Entry> remaining
    ) {
        List<Entry> live = new ArrayList<>(remaining);
        boolean stripped;
        do {
            Set<Entry> in = new HashSet<>(live);
            stripped = live.removeIf(e ->
                outgoing.getOrDefault(e, Set.of()).stream().noneMatch(in::contains));
        } while (stripped);
        List<Entry> path = new ArrayList<>();
        Entry at = live.get(0);
        while (!path.contains(at)) {
            path.add(at);
            at = outgoing.getOrDefault(at, Set.of()).stream()
                .filter(live::contains).findFirst().orElseThrow();
        }
        return new ArrayList<>(path.subList(path.indexOf(at), path.size()));
    }

    /**
     * The cycle as {@code 'a' → 'b' → 'a'} — first member repeated to show
     * the closure; id-less entries (defensive only) show their value type.
     */
    private String describeCycle(List<Entry> cycle) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : cycle) {
            if (sb.length() > 0) sb.append(" → ");
            sb.append(member(e));
        }
        return sb.append(" → ").append(member(cycle.get(0))).toString();
    }

    private String member(Entry e) {
        return e.id != null
            ? "'" + e.id + "'"
            : "<" + e.value.getClass().getName() + ">";
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

    private void warnMissing(String id, String method, Entry entry) {
        String owner = entry.id != null ? "'" + entry.id + "'" : "<unnamed>";
        LOG.warn(
            "Ordering reference to unknown id '{}' in {} of contribution {} "
                + "for extension {} is ignored. Check for a typo, or a module "
                + "that is not installed.",
            id,
            method,
            owner,
            entryType.getSimpleName()
        );
    }

    private static String normalizeOptionalId(String id) {
        if (id == null) return null;
        String v = id.trim();
        if (v.isEmpty()) throw new IllegalArgumentException(
            "Contribution id must not be blank"
        );
        return v;
    }

    private final class Entry implements Ordering {

        final String id;
        final T value;
        final List<String> beforeIds = new ArrayList<>();
        final List<String> afterIds = new ArrayList<>();

        Entry(String id, T value) {
            this.id = id;
            this.value = value;
        }

        @Override
        public Ordering before(String... ids) {
            requireMutable("before()");
            for (String s : ids) {
                beforeIds.add(Objects.requireNonNull(s, "id").trim());
            }
            invalidateOrder();
            return this;
        }

        @Override
        public Ordering after(String... ids) {
            requireMutable("after()");
            for (String s : ids) {
                afterIds.add(Objects.requireNonNull(s, "id").trim());
            }
            invalidateOrder();
            return this;
        }

        /**
         * Ordering declared via before()/after() invalidates the {@code sorted}
         * cache. Runs on the composition thread — same thread as {@link #all()}
         * during binding — so the constraint lists and the cache pointer stay
         * consistent without a lock; after the seal no mutation can race a
         * reader.
         */
        private void invalidateOrder() {
            Extension.this.sorted = null;
        }

    }
}
