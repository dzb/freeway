package com.jujin.freeway.ioc;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Entry point for running work inside a container scope boundary, backed by
 * {@link java.lang.ScopedValue} (JDK 25 JEP 506).
 * <p>
 * Each call to {@link #within(Supplier)} opens a value scope (or attaches to the
 * enclosing action scope), executes the work inside it, and closes the session
 * when its owner completes (normally or exceptionally) — running
 * {@code @PreDestroy} and {@link AutoCloseable} lifecycle on every service that
 * was realized inside the scope.
 * <p>
 * A value scope opened inside {@code Defer.within(...)} attaches to that action
 * scope: scoped services then live until the action scope closes, not until this
 * block returns. Otherwise the scope owns its session and closes at the end of
 * the work.
 * <p>
 * The boundary itself is caller-defined (an HTTP request, a consumed message, a
 * batch, a WebSocket callback, a test). The only boundary-scoped lifecycle the
 * container currently provides is {@link Scope#THREAD}; callers choose where the
 * boundary starts and ends.
 * <p>
 * Scopes nest naturally: an inner {@code within()} temporarily shadows the outer scope;
 * the outer scope is restored when the inner work returns.
 */
public interface ScopeBoundary {
    /**
     * Execute {@code work} inside a new scope boundary.
     * <p>
     * Scope-bound services resolved during the work are cached per scope session
     * and destroyed when the owning session closes — at the end of this work, or at
     * the enclosing action scope's close when this scope attached to it. The scope
     * uses {@link java.lang.ScopedValue} internally, so there is no
     * {@link ThreadLocal} overhead on virtual threads.
     *
     * @param work the code to run inside the scope
     * @param <T>  the return type
     * @return the value returned by {@code work}
     */
    <T> T within(Supplier<T> work);

    /**
     * Runs {@code work} inside a new scope boundary, for work that returns
     * nothing. Without it a side-effecting block had to be written as
     * {@code within(() -> { ...; return null; })} — the same run/call pair
     * {@link java.lang.ScopedValue} offers.
     *
     * @param work the code to run inside the scope
     */
    default void within(Runnable work) {
        within(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} inside a boundary when the work throws checked
     * exceptions. The scope is closed on both paths, and the original checked
     * exception is rethrown unchanged.
     *
     * <p>This is the form adapters such as an HTTP filter need: their
     * {@code handle}/{@code doFilter} contracts allow checked exceptions, and
     * wrapping/unwrapping them at every call site would hide the real type.</p>
     */
    default void withinChecked(ThrowingRunnable work) throws Exception {
        Objects.requireNonNull(work, "work");
        try {
            within(() -> {
                try {
                    work.run();
                } catch (Exception e) {
                    throw new CheckedWorkException(e);
                }
                return null;
            });
        } catch (CheckedWorkException e) {
            throw e.work();
        }
    }

    /** A checked-exception body, the {@code Runnable} counterpart for adapters. */
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }
}
