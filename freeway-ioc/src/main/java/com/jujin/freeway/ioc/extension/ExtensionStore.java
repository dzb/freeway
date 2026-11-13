package com.jujin.freeway.ioc.extension;

import com.jujin.freeway.commons.util.TopologicalOrder;
import com.jujin.freeway.ioc.RuntimeDescription;
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
import java.util.Set;
import java.util.function.BooleanSupplier;

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
 * <p><b>Composition-only.</b> The write side lives here, not on
 * {@link Extension}: applications receive the read-only interface from
 * {@code Container.extension(...)}, and contributions enter through
 * {@link Contribution} while modules bind. The container {@link #seal() seals}
 * every store once composition finishes: {@link #add} and
 * {@link Ordering#before}/{@link Ordering#after} are accepted only during
 * composition, never after the container is built. Readers are lock-free — the
 * data is immutable from the seal on, and the composition thread's writes are
 * published with it.
 *
 * <p>A container-created store is readable only after every contribution
 * declared for its entry type has been published; a read before that point
 * throws instead of returning the prefix that happens to have landed. This
 * is the lifecycle split: construction and publication are composition, while
 * field injection and {@code @PostConstruct} are the final build. The set is
 * complete when a read is allowed, but an entry read during that final-build
 * window may not yet have run its own {@code @PostConstruct}; a consumer that
 * needs initialized peers should read after startup (or take the deferred
 * {@code Supplier} form). The config chain ({@code SymbolProvider},
 * {@code CoerceRule}) is exempt because {@code SymbolSource} reads it live.
 *
 * @param <T> the entry type (extension point type)
 */
public final class ExtensionStore<T> implements Extension<T> {
    private static final Logger LOG = LoggerFactory.getLogger(ExtensionStore.class);

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private final Class<T> entryType;
    private volatile List<T> sorted;
    private volatile List<Entry> sortedEntries;
    private volatile Map<String, T> mapCache;
    /** Set by the container after composition; visible to every later reader. */
    private volatile boolean sealed;
    /**
     * Whether every contribution declared for this entry type has been
     * published. Container-created stores start incomplete and become
     * readable only when their last deferred entry lands; a standalone
     * {@code Extension} is complete from the start.
     */
    private final BooleanSupplier complete;

    public ExtensionStore(Class<T> entryType) {
        this(entryType, () -> true);
    }

    /**
     * The container's form: reads are refused until {@code complete} answers
     * true, so a composition-time reader can never freeze a partial view.
     * The two-arg constructor is public because the registry lives in another
     * package; application code should use the one-arg form.
     */
    public ExtensionStore(Class<T> entryType, BooleanSupplier complete) {
        this.entryType = Objects.requireNonNull(entryType, "entryType");
        this.complete = Objects.requireNonNull(complete, "complete");
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
        OrderingSupport ordering = new OrderingSupport(op -> {
            requireMutable(op);
            sorted = null;
            sortedEntries = null;
        });
        Entry entry = new Entry(normalizedId, value, ordering);
        entries.add(entry);
        sorted = null;
        sortedEntries = null;
        mapCache = null;
        return ordering;
    }

    /**
     * Returns contributions as an id→value map. Unnamed entries
     * (id=null) are excluded. Maintains insertion order.
     *
     * <p>The returned map is an immutable point-in-time snapshot: later
     * contributions are visible only through a fresh call. A container-created
     * store refuses the read until every declared contribution of this type has
     * been published, so the snapshot is never a partial view.
     *
     * @return an ordered map of named contributions
     * @throws IllegalStateException if the store is still being published
     */
    public Map<String, T> asMap() {
        requireComplete("asMap()");
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
     * <p>A container-created store refuses the read until every declared
     * contribution of this type has been published. A service realized while
     * composition is still publishing cannot freeze a prefix of the store: it
     * either sees the complete list or fails loudly.
     *
     * @return an unmodifiable list of contributed values
     * @throws IllegalStateException if the store is still being published
     */
    public List<T> all() {
        requireComplete("all()");
        List<T> cached = sorted;
        if (cached != null) {
            return cached;
        }
        List<Entry> ordered = orderedEntries();
        List<T> values = new ArrayList<>(ordered.size());
        for (Entry entry : ordered) {
            values.add(entry.value);
        }
        cached = List.copyOf(values);
        sorted = cached;
        return cached;
    }

    /**
     * Metadata snapshot of this extension point in resolved order. Values are
     * represented by their concrete class only; no contribution instance is
     * exposed through the description.
     */
    public RuntimeDescription.ExtensionDescription describe() {
        requireComplete("describe()");
        List<RuntimeDescription.ContributionDescription> contributions =
            new ArrayList<>(entries.size());
        for (Entry entry : orderedEntries()) {
            contributions.add(new RuntimeDescription.ContributionDescription(
                entry.id,
                entry.value.getClass().getName(),
                entry.ordering.beforeIds(),
                entry.ordering.afterIds(),
                entry.ordering.beforeIfPresentIds(),
                entry.ordering.afterIfPresentIds()
            ));
        }
        return new RuntimeDescription.ExtensionDescription(
            entryType.getName(), contributions);
    }

    private List<Entry> orderedEntries() {
        List<Entry> ordered = sortedEntries;
        if (ordered == null) {
            ordered = orderEntries();
            sortedEntries = ordered;
        }
        return ordered;
    }

    @Override
    public String toString() {
        return "Extension[" + entryType.getSimpleName() + "]";
    }

    /**
     * Fails fast when any required {@code before/after} ordering reference
     * points to an unknown contribution id. Conditional references
     * ({@code beforeIfPresent/afterIfPresent}) are never validated: an absent
     * optional companion is a configuration, not an error. The generic
     * {@link #all()} ordering stays lenient for required references too —
     * unknown ones are WARNed and ignored — because a missing sibling is
     * harmless for most extension points. Strict consumers (e.g. runtime-hook
     * ordering in the boot layer) call this before resolving so a typo like
     * {@code after("freeway.http.serve")} fails startup instead of silently
     * running hooks in the wrong order.
     *
     * @throws IllegalStateException naming the missing id, the ordering
     *         method, and the contribution that declared the reference
     */
    public void validateOrdering() {
        requireComplete("validateOrdering()");
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.id != null) {
                byId.put(entry.id, entry);
            }
        }
        for (Entry entry : entries) {
            for (String id : entry.ordering.afterIds()) {
                if (!byId.containsKey(id)) {
                    throw missingReference(id, "after()", entry);
                }
            }
            for (String id : entry.ordering.beforeIds()) {
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

    /**
     * Guards every read. A store with declared-but-unpublished entries has no
     * complete view to offer, so it refuses instead of returning the prefix
     * that happens to be on the composition thread's stack. The config layer
     * is exempt: {@code SymbolSource} deliberately reads its providers live.
     */
    private void requireComplete(String op) {
        if (complete.getAsBoolean()) {
            return;
        }
        String type = entryType.getSimpleName();
        throw new IllegalStateException(
            "Extension " + type + " is still being published — " + op +
                " would return a partial view. Inject Supplier<List<" + type +
                ">> (or Supplier<Map<String, " + type + ">>) and read it after " +
                "startup, or read it once the container is built."
        );
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

    private List<Entry> orderEntries() {
        if (entries.isEmpty()) {
            return List.of();
        }
        if (
            entries
                .stream()
                .allMatch(e -> e.ordering.isEmpty())
        ) {
            return List.copyOf(entries);
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
        for (Entry entry : entries) {
            outgoing.put(entry, new LinkedHashSet<>());
        }

        for (Entry entry : entries) {
            for (String id : entry.ordering.afterIds()) {
                Entry dep = byId.get(id);
                if (dep == null) {
                    warnMissing(id, "after()", entry);
                    continue;
                }
                addEdge(dep, entry, outgoing);
            }
            for (String id : entry.ordering.beforeIds()) {
                Entry target = byId.get(id);
                if (target == null) {
                    warnMissing(id, "before()", entry);
                    continue;
                }
                addEdge(entry, target, outgoing);
            }
            // Conditional references are vacuous when absent — no warning:
            // absence is an expected configuration, and warning on every
            // startup would punish every legitimate subset composition.
            for (String id : entry.ordering.afterIfPresentIds()) {
                Entry dep = byId.get(id);
                if (dep == null) {
                    continue;
                }
                addEdge(dep, entry, outgoing);
            }
            for (String id : entry.ordering.beforeIfPresentIds()) {
                Entry target = byId.get(id);
                if (target == null) {
                    continue;
                }
                addEdge(entry, target, outgoing);
            }
        }

        TopologicalOrder.Result<Entry> result = TopologicalOrder.sort(
            entries, outgoing, Comparator.comparingInt(positions::get));
        if (!result.complete()) {
            // Kahn stalled, so the unprocessed subgraph contains at least one
            // cycle — name its members, or the error only says which
            // extension point to stare at.
            throw new IllegalStateException(
                "Contribution order cycle detected for extension "
                    + entryType.getSimpleName()
                    + ": "
                    + describeCycle(findCycle(outgoing, result.remaining()))
            );
        }
        return List.copyOf(result.ordered());
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
        Map<Entry, Set<Entry>> outgoing
    ) {
        if (from != to) {
            outgoing.get(from).add(to);
        }
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

    private final class Entry {

        final String id;
        final T value;
        final OrderingSupport ordering;

        Entry(String id, T value, OrderingSupport ordering) {
            this.id = id;
            this.value = value;
            this.ordering = ordering;
        }
    }
}
