package com.jujin.freeway.commons.scoped;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * Scope-bound deferred execution. Actions registered via {@link #defer(Runnable)}
 * are held until the enclosing action scope completes successfully, then executed
 * in dependency order. If the scope throws (or {@link #rollback()} is called),
 * deferred actions are discarded.
 *
 * <p>An action scope is a {@link ScopeSession} with the action capability; a
 * {@link ScopedCache} value scope opened inside it attaches its values to the same
 * session, so deferred actions always run while cached values are still open —
 * whichever order the two are nested in.</p>
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
 * Defer.within(() -> {
 *     handle(request);
 *     if (bad) { Defer.rollback(); return; }
 * });
 * }</pre>
 *
 * <h3>Ordered actions</h3>
 * <pre>{@code
 * Defer.within(() -> {
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

    private Defer() {}

    // ==================== scope ====================

    /**
     * Returns true while the current action scope can still accept deferred
     * actions — inside {@link #within} and before its drain begins.
     *
     * <p>During drain this is false: the scope is closing, so an action
     * registered from a deferred action runs immediately instead of extending
     * the drain.</p>
     */
    public static boolean isActive() {
        ScopeSession current = ScopeSession.current();
        return current != null && current.acceptsActions();
    }

    /**
     * Opens an action scope. Deferred actions drain on success, discard on
     * failure.
     */
    public static void within(Runnable work) {
        Objects.requireNonNull(work, "work");
        withinScope(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Opens an action scope that yields the work's result. Deferred actions
     * drain on normal return (unless rollback was called), discard on
     * exception — then the result is never produced.
     */
    public static <T> T within(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        return withinScope(work);
    }

    /**
     * Marks the current action scope for rollback: its deferred actions are
     * discarded when it exits normally. Outside an action scope this is a no-op.
     *
     * <p>Rollback belongs to the action façade, not to the shared session: a
     * cache handle cannot roll back the action scope it attached to.</p>
     */
    public static void rollback() {
        ScopeSession current = ScopeSession.current();
        if (current != null && current.acceptsActions()) {
            current.markRolledBack();
        }
    }

    private static <T> T withinScope(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        ScopeSession scope = new ScopeSession(true, false);
        // Close inside the binding: deferred actions are part of the scope and
        // must still see values attached to it.
        return ScopedValue.where(ScopeSession.CURRENT, scope).call(() -> {
            try {
                T result = work.get();
                scope.closeAfterSuccess();
                return result;
            } catch (Throwable t) {
                scope.closeAfterFailure();
                throw t;
            }
        });
    }

    // ==================== defer ====================

    /**
     * If inside an open action scope, enqueues the un-named action. Otherwise
     * (outside, or while the scope is draining) runs it immediately.
     */
    public static void defer(Runnable action) {
        Objects.requireNonNull(action, "action");
        ScopeSession current = ScopeSession.current();
        if (current != null && current.acceptsActions()) {
            current.addAction(new DeferAction(null, action));
        } else {
            action.run();
        }
    }

    /**
     * If inside an open action scope, enqueues a named action and returns a
     * handle for ordering via {@link DeferAction#before} / {@link DeferAction#after}.
     * Otherwise runs {@code action} immediately and returns a no-op handle.
     */
    public static DeferAction defer(String id, Runnable action) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        ScopeSession current = ScopeSession.current();
        if (current != null && current.acceptsActions()) {
            DeferAction da = new DeferAction(id, action);
            current.addAction(da);
            return da;
        }
        action.run();
        return DeferAction.NOOP;
    }

    // ==================== supply ====================

    /**
     * If inside an open action scope, returns a {@link Supplier} whose
     * {@code get()} computes the value on first access and caches it; if it
     * is never accessed, the value is computed when the scope commits.
     * Outside an action scope, the supplier computes on each {@code get()}
     * call.
     */
    public static <T> Supplier<T> supply(Callable<T> callable) {
        Objects.requireNonNull(callable, "callable");
        ScopeSession current = ScopeSession.current();
        if (current != null && current.acceptsActions()) {
            DeferredSupplier<T> ds = new DeferredSupplier<>(callable);
            current.addAction(new DeferAction(null, ds::compute));
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
        ScopeSession current = ScopeSession.current();
        if (current != null && current.acceptsActions()) {
            DeferAction da = new DeferAction(id, ds::compute, ds);
            current.addAction(da);
            return da;
        }
        // Outside an action scope, run immediately — a silently dropped
        // callable would be a footgun. The value stays available via value().
        ds.compute();
        return new DeferAction(id, ds::compute, ds);
    }

    // ==================== internal ====================

    private static final class DeferredSupplier<T> implements Supplier<T> {

        private final Callable<T> callable;
        private T value;
        private boolean computed;
        private Throwable failure;
        /** Guards the exactly-once protocol below. */
        private final Object lock = new Object();

        private static final String FAILURE_MESSAGE = "Defer supply computation failed";

        DeferredSupplier(Callable<T> callable) {
            this.callable = callable;
        }

        void compute() {
            synchronized (lock) {
                if (!computed) {
                    runOnce();
                }
            }
        }

        @Override
        public T get() {
            synchronized (lock) {
                if (!computed) {
                    runOnce();
                }
                return value;
            }
        }

        private void runOnce() {
            if (failure != null) {
                rethrowCached();
            }
            try {
                value = callable.call();
                computed = true; // drain must not re-execute after get() succeeded
            } catch (Error err) {
                failure = err;
                throw err;
            } catch (Throwable t) {
                RuntimeException wrapped = new RuntimeException(FAILURE_MESSAGE, t);
                failure = wrapped;
                throw wrapped;
            }
        }

        private void rethrowCached() {
            if (failure instanceof Error err) {
                throw err;
            }
            if (failure instanceof RuntimeException re) {
                throw re;
            }
            throw new AssertionError(
                "unreachable: supply failures are Errors or wrapped", failure);
        }
    }
}
