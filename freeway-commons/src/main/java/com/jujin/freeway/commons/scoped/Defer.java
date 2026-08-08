package com.jujin.freeway.commons.scoped;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Scope-bound deferred execution. Actions registered via {@link #defer(Runnable)}
 * are held until the enclosing scope completes successfully, then executed in
 * dependency order. If the scope throws (or {@link DeferScope#rollback()} is
 * called), deferred actions are discarded.
 *
 * <h3>Basic usage</h3>
 * <pre>{@code
 * Defer.within(() -> {
 *     Defer.defer(() -> cache.clear());
 *     Defer.defer(() -> metrics.record());
 * });
 * }</pre>
 *
 * <h3>Manual rollback</h3>
 * <pre>{@code
 * Defer.within(scope -> {
 *     handle(request);
 *     if (bad) { scope.rollback(); return; }
 * });
 * }</pre>
 *
 * <h3>Ordered actions</h3>
 * <pre>{@code
 * Defer.within(scope -> {
 *     Defer.defer("index",  () -> rebuildIndex()).after("cache");
 *     Defer.defer("cache",  () -> clearCache());
 *     Defer.defer("notify", () -> notifyUsers()).after("index", "cache");
 * });
 * // Execution order: cache → index → notify
 * }</pre>
 *
 * <h3>Deferred value</h3>
 * <pre>{@code
 * var snap = Defer.supply(() -> buildSnapshot());
 * // supplier.get() computes immediately if no scope, else at commit time
 * }</pre>
 */
public final class Defer {

    private static final ScopedValue<DeferScope> CURRENT =
        ScopedValue.newInstance();

    private Defer() {}

    // ==================== scope ====================

    /**
     * Wraps an executor so tasks submitted inside a {@link #within} scope
     * run on the worker with the submitting thread's Defer scope restored —
     * the scope-binding (and thus the pending deferred actions) is visible
     * on the worker, so a task can e.g. call {@link #isActive()} or register
     * further deferred work.
     *
     * <p>Opt-in by design: async work does NOT inherit the scope on a plain
     * executor (see {@link com.jujin.freeway.commons.util.ContextExecutor}).
     *
     * <pre>{@code
     * Executor txExecutor = Defer.with(myPool);
     * Defer.within(() -> txExecutor.execute(() -> stillInScope()));
     * }</pre>
     */
    public static java.util.concurrent.Executor with(
        java.util.concurrent.Executor delegate
    ) {
        return com.jujin.freeway.commons.util.ContextExecutor.wrapping(
            delegate, CURRENT);
    }

    /** Returns true if called inside a {@link #within} block. */
    public static boolean isActive() {
        return CURRENT.isBound();
    }

    /**
     * Opens a deferred-execution scope. Deferred actions drain on success,
     * discard on failure.
     */
    public static void within(Runnable work) {
        Objects.requireNonNull(work, "work");
        within(scope -> work.run());
    }

    /**
     * Opens a deferred-execution scope with a {@link DeferScope} handle for
     * manual {@link DeferScope#rollback()}. Actions drain on normal return
     * (unless rollback was called), discard on exception.
     *
     * <p><b>Nesting with {@link ScopedCache}:</b> the cache scope must wrap
     * this Defer scope ({@code ScopedCache.within(() -> Defer.within(...))})
     * so deferred actions run while cached values are still open. The reverse
     * nesting ({@code Defer.within(() -> ScopedCache.within(...))}) cleans up
     * cached values when the inner cache scope exits — before any deferred
     * action registered in this scope runs — so such actions must not touch
     * cached resources. {@link ScopedCache} detects that nesting at runtime
     * and warns.
     */
    public static void within(Consumer<DeferScope> work) {
        Objects.requireNonNull(work, "work");
        DeferScope scope = new DeferScope();
        try {
            ScopedValue.where(CURRENT, scope).run(() -> work.accept(scope));
            if (!scope.isRolledBack()) {
                scope.drain();
            } else {
                scope.discard();
            }
        } catch (Throwable t) {
            scope.discard();
            throw t;
        }
    }

    // ==================== defer ====================

    /**
     * If inside a {@link #within} block, enqueues the un-named action.
     * Otherwise runs it immediately.
     */
    public static void defer(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (CURRENT.isBound()) {
            CURRENT.get().add(new DeferAction(null, action));
        } else {
            action.run();
        }
    }

    /**
     * If inside a {@link #within} block, enqueues a named action and
     * returns a handle for ordering via {@link DeferAction#before} /
     * {@link DeferAction#after}. Otherwise runs {@code action} immediately
     * and returns a no-op handle.
     */
    public static DeferAction defer(String id, Runnable action) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        if (CURRENT.isBound()) {
            DeferAction da = new DeferAction(id, action);
            CURRENT.get().add(da);
            return da;
        }
        action.run();
        return DeferAction.NOOP;
    }

    // ==================== supply ====================

    /**
     * If inside a {@link #within} block, returns a {@link Supplier} whose
     * {@code get()} computes the value on first access and caches it; if it
     * is never accessed, the value is computed when the scope commits.
     * Outside a scope, the supplier computes on each {@code get()} call.
     */
    public static <T> Supplier<T> supply(Callable<T> callable) {
        Objects.requireNonNull(callable, "callable");
        if (CURRENT.isBound()) {
            DeferredSupplier<T> ds = new DeferredSupplier<>(callable);
            CURRENT.get().add(new DeferAction(null, ds::compute));
            return ds;
        }
        return () -> {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new RuntimeException("Defer.supply failed", e);
            }
        };
    }

    /**
     * Named variant of {@link #supply(Callable)} — the returned handle
     * supports {@link DeferAction#before} / {@link DeferAction#after}
     * for ordering relative to other named actions, and
     * {@link DeferAction#value()} to retrieve the computed value. Outside a
     * scope the callable runs immediately and the handle still exposes the
     * value.
     */
    public static <T> DeferAction supply(String id, Callable<T> callable) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(callable, "callable");
        DeferredSupplier<T> ds = new DeferredSupplier<>(callable);
        if (CURRENT.isBound()) {
            DeferAction da = new DeferAction(id, ds::compute, ds);
            CURRENT.get().add(da);
            return da;
        }
        // Outside a scope, run immediately — a silently dropped callable
        // would be a footgun. The value stays available via value().
        ds.compute();
        return new DeferAction(id, ds::compute, ds);
    }

    // ==================== internal ====================

    private static final class DeferredSupplier<T> implements Supplier<T> {

        private final Callable<T> callable;
        private T value;
        private boolean computed;
        private RuntimeException failure;

        private static final String FAILURE_MESSAGE = "Defer supply computation failed";

        DeferredSupplier(Callable<T> callable) {
            this.callable = callable;
        }

        synchronized void compute() {
            if (computed) {
                return;
            }
            if (failure != null) {
                throw failure;
            }
            try {
                value = callable.call();
                computed = true;
            } catch (Throwable t) {
                // Errors are cached too: a supply whose callable throws an
                // Error must not re-execute the side-effecting callable on
                // the next access.
                failure = new RuntimeException(FAILURE_MESSAGE, t);
                throw failure;
            }
        }

        @Override
        public synchronized T get() {
            if (computed) {
                return value;
            }
            if (failure != null) {
                throw failure;
            }
            try {
                T v = callable.call();
                value = v;
                computed = true; // drain must not re-execute after get() succeeded
                return v;
            } catch (Throwable t) {
                // Cache the failure so every subsequent access (and the
                // drain) rethrows it — a failed supply must not silently
                // degrade to null.
                failure = new RuntimeException(FAILURE_MESSAGE, t);
                throw failure;
            }
        }
    }
}
