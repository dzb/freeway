package com.jujin.freeway.ioc;

import java.util.function.Supplier;

/**
 * Thread-scope entry point backed by {@link java.lang.ScopedValue} (JDK 25 JEP 506).
 * <p>
 * Each call to {@link #within(Supplier)} creates a new scope session, executes the
 * work inside it, and automatically closes the session when the work completes
 * (normally or exceptionally) — running {@code @PreDestroy} and
 * {@link AutoCloseable} lifecycle on every service that was realized inside the scope.
 * <p>
 * Scopes nest naturally: an inner {@code within()} temporarily shadows the outer scope;
 * the outer scope is restored when the inner work returns.
 */
public interface Scoping {
    /**
     * Execute {@code work} inside a new thread scope.
     * <p>
     * Thread-scoped services resolved during the work are cached per scope session
     * and destroyed on exit. The scope uses {@link java.lang.ScopedValue} internally
     * so there is no {@link ThreadLocal} overhead on virtual threads.
     *
     * @param work the code to run inside the scope
     * @param <T>  the return type
     * @return the value returned by {@code work}
     */
    <T> T within(Supplier<T> work);
}
