package com.jujin.freeway.ioc;

import java.util.function.Supplier;

/**
 * Thread-scope entry point backed by {@link java.lang.ScopedValue} (JDK 25 JEP 506).
 * <p>
 * Each call to {@link #within(Supplier)} opens a value scope (or attaches to the
 * enclosing action scope), executes the work inside it, and closes the session
 * when its owner completes (normally or exceptionally) — running
 * {@code @PreDestroy} and {@link AutoCloseable} lifecycle on every service that
 * was realized inside the scope.
 * <p>
 * A value scope opened inside {@code Defer.within(...)} attaches to that action
 * scope: thread-scoped services then live until the action scope closes, not
 * until this block returns. Otherwise the scope owns its session and closes at
 * the end of the work.
 * <p>
 * Scopes nest naturally: an inner {@code within()} temporarily shadows the outer scope;
 * the outer scope is restored when the inner work returns.
 */
public interface Scoping {
    /**
     * Execute {@code work} inside a new thread scope.
     * <p>
     * Thread-scoped services resolved during the work are cached per scope session
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
     * Runs {@code work} inside a new thread scope, for work that returns
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
}
