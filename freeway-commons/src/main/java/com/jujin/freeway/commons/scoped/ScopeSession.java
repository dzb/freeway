package com.jujin.freeway.commons.scoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One scope state behind {@link Defer} and {@link ScopedCache}: the deferred
 * actions of an action scope and the cached values of a value scope share one
 * session object, one scope binding ({@code CURRENT}), one value-access view
 * ({@code CACHE}) and one close algorithm.
 *
 * <p>{@link Defer} opens an action scope; {@link ScopedCache} opens a value
 * scope, or attaches its values to an enclosing action scope. Values therefore
 * always outlive the actions of the scope that owns them — the close order is
 * actions, then values — so the "cache outside Defer" rule is no longer a
 * caller obligation.</p>
 *
 * <p>The public part is a read-only token: {@link #isActionScope()} and
 * {@link #isClosed()}. Opening, closing and rollback are owned by the two
 * façades; a cache handle therefore cannot steer the action scope it attached
 * to.</p>
 */
public final class ScopeSession {

    private static final Logger LOG = LoggerFactory.getLogger(ScopeSession.class);

    /** The one current-session binding both façades read. */
    static final ScopedValue<ScopeSession> CURRENT = ScopedValue.newInstance();

    /** The value-access view: bound only while a cache block (or an action drain) is open. */
    static final ScopedValue<ScopeSession> CACHE = ScopedValue.newInstance();

    private static final List<Consumer<Object>> ON_CLOSE = new CopyOnWriteArrayList<>();
    /** Warn threshold for accumulated {@link #addOnClose} handlers. */
    private static final int ON_CLOSE_WARN_THRESHOLD = 16;

    /** An action scope defers actions; a value scope only holds cached values. */
    private final boolean actions;
    /** False once the scope starts closing: actions registered later run immediately. */
    private volatile boolean actionsOpen = true;
    /** True when values may live here (a value scope, or an action scope a cache attached to). */
    private volatile boolean valuesCapable;

    private final List<DeferAction> pending = new ArrayList<>();
    private final Set<String> ids = new LinkedHashSet<>();
    private boolean rolledBack;

    private final Map<Object, Object> cache = new LinkedHashMap<>();
    private final Map<Object, Consumer<Object>> cleanups = new LinkedHashMap<>();
    private final Object lock = new Object();
    private volatile boolean closed;

    ScopeSession(boolean actions, boolean valuesCapable) {
        this.actions = actions;
        this.valuesCapable = valuesCapable;
    }

    static ScopeSession current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /** True for an action scope, including while it closes (values may still attach). */
    public boolean isActionScope() {
        return actions;
    }

    /** True once this session's values have been released. */
    public boolean isClosed() {
        return closed;
    }

    /** True while deferred actions may still be registered on this action scope. */
    boolean acceptsActions() {
        return actions && actionsOpen;
    }

    /** Enables values on this scope — used when a value scope attaches. */
    void enableValues() {
        valuesCapable = true;
    }

    /** Mark this action scope for rollback; called only by {@code Defer.rollback()}. */
    void markRolledBack() {
        rolledBack = true;
    }

    // ==================== actions ====================

    void addAction(DeferAction action) {
        if (action.id() != null && !ids.add(action.id())) {
            throw new IllegalStateException(
                "Duplicate deferred action id '" + action.id() + "'"
            );
        }
        pending.add(action);
    }

    void closeAfterSuccess() {
        actionsOpen = false;
        IllegalStateException orderingFailure = null;
        if (!rolledBack) {
            try {
                drain();
            } catch (IllegalStateException ex) {
                orderingFailure = ex;
            }
        } else {
            discard();
        }
        closeValues();
        if (orderingFailure != null) {
            throw orderingFailure;
        }
    }

    void closeAfterFailure() {
        actionsOpen = false;
        discard();
        closeValues();
    }

    // ==================== values ====================

    Object getOrCreate(Object key, Supplier<Object> factory, Consumer<Object> onExit) {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Session is closed");
            }
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            Object created = factory.get();
            cache.put(key, created);
            if (onExit != null) {
                cleanups.putIfAbsent(key, onExit);
            }
            return created;
        }
    }

    void closeValues() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            List<Map.Entry<Object, Object>> snapshot = cache.entrySet().stream()
                    .filter(e -> e.getValue() != null).toList();
            Map<Object, Consumer<Object>> pendingCleanups = new LinkedHashMap<>(cleanups);
            cache.clear();
            cleanups.clear();
            if (snapshot.isEmpty()) {
                return;
            }
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map.Entry<Object, Object> entry : snapshot) {
                Object value = entry.getValue();
                if (!seen.add(value)) {
                    continue;
                }
                Consumer<Object> cleanup = pendingCleanups.get(entry.getKey());
                if (cleanup != null) {
                    try {
                        cleanup.accept(value);
                    } catch (Throwable ex) {
                        LOG.warn("ScopedCache cleanup failed", ex);
                    }
                }
                for (Consumer<Object> handler : ON_CLOSE) {
                    try {
                        handler.accept(value);
                    } catch (Throwable ex) {
                        LOG.warn("ScopedCache cleanup handler failed", ex);
                    }
                }
            }
        }
    }

    // ==================== global cleanup handlers ====================

    static void addOnClose(Consumer<Object> handler) {
        Objects.requireNonNull(handler, "handler");
        ON_CLOSE.add(handler);
        int count = ON_CLOSE.size();
        if (count > ON_CLOSE_WARN_THRESHOLD) {
            LOG.warn(
                "ScopedCache.onClose handler count is {} — handlers accumulate process-wide; "
                    + "register once at startup or pair with removeOnClose to avoid a leak",
                count
            );
        }
    }

    static void removeOnClose(Consumer<Object> handler) {
        Objects.requireNonNull(handler, "handler");
        ON_CLOSE.remove(handler);
    }

    static void resetOnClose() {
        ON_CLOSE.clear();
    }

    // ==================== drain ====================

    private void drain() {
        IllegalStateException orderingFailure = null;
        List<Runnable> ordered;
        try {
            ordered = sort(pending);
        } catch (IllegalStateException ex) {
            // Ordering is impossible (circular before/after constraint). Never
            // drop the actions — cleanup must still run even when relative
            // order cannot be satisfied. Fall back to registration order and
            // rethrow below so the constraint bug is still visible.
            LOG.error(
                "Deferred action ordering failed; running all actions in registration order",
                ex
            );
            orderingFailure = ex;
            ordered = new ArrayList<>(pending.size());
            for (DeferAction action : pending) {
                ordered.add(action.action());
            }
        }
        for (Runnable action : ordered) {
            try {
                // A deferred action is part of the scope: while it runs, values
                // stored here are visible through the cache view. An action scope
                // with no values leaves any outer cache view in place.
                if (valuesCapable) {
                    ScopedValue.where(CACHE, this).run(action);
                } else {
                    action.run();
                }
            } catch (Throwable ex) {
                // Errors included: one failing action must not skip the rest.
                LOG.warn("Deferred action failed", ex);
            }
        }
        if (orderingFailure != null) {
            throw orderingFailure;
        }
    }

    private void discard() {
        pending.clear();
        ids.clear();
    }

    /**
     * Sorts actions for drain. Result order:
     * <ol>
     *   <li>constrained named actions — topological order by before/after</li>
     *   <li>unconstrained named actions — registration order</li>
     *   <li>unnamed actions — registration order</li>
     * </ol>
     */
    static List<Runnable> sort(List<DeferAction> actions) {
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

        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (String id : constrainedIds) {
            indegree.put(id, 0);
            edges.put(id, new ArrayList<>());
        }

        for (DeferAction a : actions) {
            if (a.id() == null || !constrainedIds.contains(a.id())) continue;
            for (String other : a.before()) {
                if (!byId.containsKey(other)) continue;
                edges.computeIfAbsent(a.id(), k -> new ArrayList<>()).add(other);
                indegree.merge(other, 1, Integer::sum);
            }
            for (String other : a.after()) {
                if (!byId.containsKey(other)) continue;
                edges.computeIfAbsent(other, k -> new ArrayList<>()).add(a.id());
                indegree.merge(a.id(), 1, Integer::sum);
            }
        }

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

        List<Runnable> result = new ArrayList<>();
        Map<String, DeferAction> remaining = new LinkedHashMap<>(byId);
        for (String id : orderedIds) {
            DeferAction a = remaining.remove(id);
            if (a != null) result.add(a.action());
        }
        for (DeferAction a : actions) {
            if (a.id() != null && remaining.containsKey(a.id())) {
                result.add(a.action());
                remaining.remove(a.id());
            }
        }
        for (DeferAction a : actions) {
            if (a.id() == null) result.add(a.action());
        }
        return result;
    }
}
