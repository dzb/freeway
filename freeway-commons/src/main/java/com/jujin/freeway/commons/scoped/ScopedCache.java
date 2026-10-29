package com.jujin.freeway.commons.scoped;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Scope-bound value cache. Values created via {@link #get(Object, Supplier)}
 * inside a {@link #within} block are cached per scope session and cleaned up
 * when the owning scope exits.
 *
 * <p>The value <em>lifetime</em> and the value <em>access window</em> are
 * separate facts. Inside an action scope ({@link Defer#within}) the values are
 * stored on that action session and are cleaned when it closes, but they are
 * exposed to application code only while a cache block is open (and to
 * deferred actions while the action scope drains). Outside that window
 * {@link #get} creates uncached values and {@link #isActive()} is false, even
 * if the enclosing action scope still holds values.</p>
 *
 * <p>Outside any scope, {@code get} creates values without caching and without
 * cleanup; the caller owns them.</p>
 */
public final class ScopedCache {

    private ScopedCache() {}

    // ==================== scope ====================

    /** Returns true while a cache block (or an action drain) exposes cached values. */
    public static boolean isActive() {
        return cacheSession() != null;
    }

    /** Returns the read-only session token while values are exposed, or null. */
    public static ScopeSession currentSession() {
        return cacheSession();
    }

    /** The session the cache view is bound to, or null when no value access is open. */
    private static ScopeSession cacheSession() {
        return ScopeSession.CACHE.isBound() ? ScopeSession.CACHE.get() : null;
    }

    /**
     * Opens a value scope, or attaches to an enclosing action scope. Cached
     * values are cleaned up when the owning scope exits (normally or
     * exceptionally).
     */
    public static void within(Runnable work) {
        Objects.requireNonNull(work, "work");
        within(session -> {
            work.run();
            return null;
        });
    }

    /** As {@link #within(Runnable)}, returning the work's value. */
    public static <T> T within(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return within(session -> work.get());
    }

    /**
     * Opens a value scope with a read-only {@link ScopeSession} token. Inside
     * an action scope the token is that action scope and values stay attached
     * to it; the token exposes no rollback or close. Otherwise a new value
     * scope is opened and closed here.
     */
    public static <T> T within(Function<ScopeSession, T> work) {
        Objects.requireNonNull(work, "work");
        ScopeSession outer = ScopeSession.current();
        if (outer != null && outer.isActionScope()) {
            outer.enableValues();
            // Values live on the action scope; this binding only opens the
            // access window for the block (and restores any outer cache view).
            return ScopedValue.where(ScopeSession.CACHE, outer)
                .call(() -> work.apply(outer));
        }
        ScopeSession scope = new ScopeSession(false, true);
        try {
            return ScopedValue.where(ScopeSession.CURRENT, scope).call(() ->
                ScopedValue.where(ScopeSession.CACHE, scope)
                    .call(() -> work.apply(scope)));
        } finally {
            scope.closeValues();
        }
    }

    // ==================== get ====================

    /**
     * Inside a cache window, returns the cached value for {@code key},
     * creating it via {@code factory} on first access. Outside a window —
     * including other threads, since {@link ScopedValue} does not propagate —
     * creates and returns without caching and without cleanup registration;
     * the caller owns the value and its lifecycle.
     */
    @SuppressWarnings("unchecked")
    public static <V> V get(Object key, Supplier<V> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        ScopeSession session = cacheSession();
        if (session != null) {
            return (V) session.getOrCreate(key, (Supplier<Object>) factory, null);
        }
        return factory.get();
    }

    /**
     * Inside a cache window, returns the cached value for {@code key}, creating
     * it via {@code factory} on first access and running {@code onExit} with it
     * once when the owning scope exits. Outside a window, creates and returns
     * without caching and without cleanup registration; {@code onExit} never
     * runs. A cached key ignores later registrations (factory and cleanup
     * alike): first creation wins.
     */
    @SuppressWarnings("unchecked")
    public static <V> V get(Object key, Supplier<V> factory, Consumer<V> onExit) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        ScopeSession session = cacheSession();
        if (session != null) {
            return (V) session.getOrCreate(
                key,
                (Supplier<Object>) factory,
                onExit == null ? null : v -> onExit.accept((V) v));
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
        ScopeSession.addOnClose(handler);
    }

    /** Unregisters a previously registered close handler. */
    public static void removeOnClose(Consumer<Object> handler) {
        ScopeSession.removeOnClose(handler);
    }

    static void resetCleanups() {
        ScopeSession.resetOnClose();
    }
}
