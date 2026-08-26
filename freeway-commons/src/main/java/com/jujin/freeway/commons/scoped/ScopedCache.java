package com.jujin.freeway.commons.scoped;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scope-bound value cache. Values created via {@link #get(Object, Supplier)}
 * inside a {@link #within} block are cached per scope session and cleaned up
 * when the scope exits. Outside a scope, {@code get} creates values without
 * caching.
 *
 * <h3>Basic usage</h3>
 * <pre>{@code
 * ScopedCache.within(() -> {
 *     Connection conn = ScopedCache.get(ds, () -> createConnection());
 *     conn.query(...);
 * });
 * // conn is cleaned up on scope exit
 * }</pre>
 */
public final class ScopedCache {
    private static final Logger LOG = LoggerFactory.getLogger(ScopedCache.class);

    private static final ScopedValue<Session> CURRENT = ScopedValue.newInstance();
    private static final List<Consumer<Object>> ON_CLOSE = new CopyOnWriteArrayList<>();

    private ScopedCache() {}

    // ==================== scope ====================

    /** Returns true if called inside a {@link #within} block. */
    public static boolean isActive() {
        return CURRENT.isBound();
    }

    /** Returns the current scope, or null if outside a scope. */
    public static Session currentSession() {
        if (!CURRENT.isBound()) {
            return null;
        }
        return CURRENT.get();
    }

    /**
     * Opens a scoped cache. Cached values are cleaned up when the
     * scope exits (normally or exceptionally).
     */
    public static void within(Runnable work) {
        Objects.requireNonNull(work, "work");
        within(session -> {
            work.run();
            return null;
        });
    }

    /**
     * Opens a scoped cache and returns the work's result.
     * Cached values are cleaned up on exit.
     */
    public static <T> T within(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return within(session -> work.get());
    }

    /**
     * Opens a scoped cache with a {@link Session} handle for
     * advanced use (tracking, manual close). Cached values are
     * cleaned up when the scope exits.
     *
     * <p><b>Nesting with {@link Defer}:</b> this cache scope must wrap the
     * Defer scope ({@code ScopedCache.within(() -> Defer.within(...))}) —
     * deferred actions then run while cached values are still open. The
     * reverse nesting ({@code Defer.within(() -> ScopedCache.within(...))})
     * cleans up cached values when this scope exits, <em>before</em> outer
     * Defer actions run; such actions must not touch cached resources. The
     * reverse nesting is detected at runtime and reported with a warning.
     */
    public static <T> T within(Function<Session, T> work) {
        Objects.requireNonNull(work, "work");
        if (Defer.isActive()) {
            warnDeferNesting();
        }
        Session session = new Session();
        try {
            return ScopedValue.where(CURRENT, session).call(() -> work.apply(session));
        } finally {
            session.close();
        }
    }

    /**
     * ScopedCache and Defer are independent scoped primitives with no ordering
     * contract between them. The safe nesting is cache-outer / Defer-inner:
     * deferred actions then run while cached values are still open. With
     * Defer-outer / cache-inner the cache cleanup runs when this scope exits,
     * before the outer Defer drain, so deferred actions may see closed
     * resources. Warn once per JVM — the nesting is a structural error the
     * caller should fix, and repeated warnings would only add noise.
     */
    private static volatile boolean deferNestingWarned;

    private static void warnDeferNesting() {
        if (deferNestingWarned) {
            return;
        }
        deferNestingWarned = true;
        LOG.warn(
            "ScopedCache.within() opened inside an active Defer scope: cached values are "
                + "cleaned up when this cache scope exits, BEFORE deferred actions in the "
                + "outer Defer scope run — such actions may see the resources already closed. "
                + "Nest ScopedCache OUTSIDE Defer.within instead."
        );
    }

    static void resetDeferNestingWarning() {
        deferNestingWarned = false;
    }

    // ==================== get ====================

    /**
     * Inside a scope, returns the cached value for {@code key},
     * creating it via {@code factory} on first access.
     * Outside a scope — including other threads, since {@link ScopedValue}
     * does not propagate — creates and returns without caching and without
     * cleanup registration; the caller owns the value and its lifecycle.
     */
    @SuppressWarnings("unchecked")
    public static <V> V get(Object key, Supplier<V> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        if (CURRENT.isBound()) {
            Session session = CURRENT.get();
            return (V) session.getOrCreate(key, (Supplier<Object>) factory);
        }
        return factory.get();
    }

    // ==================== cleanup ====================

    /**
     * Registers a global close handler invoked with every cached value when a
     * scope exits. Handlers accumulate process-wide; use
     * {@link #removeOnClose(Consumer)} to unregister, or keep registration to
     * startup-time one-time calls.
     */
    public static void onClose(Consumer<Object> handler) {
        Objects.requireNonNull(handler, "handler");
        ON_CLOSE.add(handler);
    }

    /** Unregisters a previously registered close handler. */
    public static void removeOnClose(Consumer<Object> handler) {
        Objects.requireNonNull(handler, "handler");
        ON_CLOSE.remove(handler);
    }

    static void resetCleanups() {
        ON_CLOSE.clear();
    }

    // ==================== scope handle ====================

    /**
     * A scoped cache session. Created automatically by
     * {@link ScopedCache#within}; can be tracked by framework
     * code for shutdown safety.
     */
    public static final class Session {
        private final Map<Object, Object> cache = new LinkedHashMap<>();
        private volatile boolean closed;

        Session() {}

        synchronized Object getOrCreate(Object key, Supplier<Object> factory) {
            if (closed) {
                throw new IllegalStateException("Session is closed");
            }
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            Object created = factory.get();
            cache.put(key, created);
            return created;
        }

        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (ON_CLOSE.isEmpty()) {
                cache.clear();
                return;
            }
            List<Object> values = cache.values().stream()
                    .filter(v -> v != null).toList();
            cache.clear();
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object value : values) {
                if (!seen.add(value)) {
                    continue;
                }
                for (Consumer<Object> handler : ON_CLOSE) {
                    try {
                        handler.accept(value);
                    } catch (Throwable ex) {
                        // Errors included: cleanup must always run to
                        // completion — one failing handler must not skip
                        // the rest (matches DeferScope.drain).
                        LOG.warn("ScopedCache cleanup handler failed", ex);
                    }
                }
            }
        }
    }
}
