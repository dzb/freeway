package com.jujin.freeway.commons.util;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Wraps an {@link Executor} so each task runs with the submitting thread's
 * bindings for the listed {@link ScopedValue} keys.
 *
 * <p>ScopedValue bindings do NOT propagate to child threads: an async task
 * submitted to a plain executor sees no {@code Defer} scope, no
 * thread-scoped values, no transaction context. This wrapper makes the
 * propagation explicit — the framework defaults to "no propagation" (async
 * work is deliberately decoupled from the submitting scope), and callers
 * who DO want a context value to reach the worker name the keys once:
 *
 * <pre>{@code
 * Executor ctxExecutor = ContextualExecutor.wrapping(pool, TX_ID);
 * // inside ScopedValue.where(TX_ID, "tx-1", ...):
 * ctxExecutor.execute(() -> workerSees("tx-1"));
 * }</pre>
 *
 * <p>Semantics: at submission time each listed key's current binding on the
 * submitting thread is captured (keys with no binding are skipped); the
 * task then runs with those bindings restored. Nested submissions from a
 * worker re-capture the worker's own (possibly empty) bindings.
 */
public final class ContextualExecutor {

    private ContextualExecutor() {}

    /**
     * Returns an executor that restores the submitting thread's bindings for
     * {@code keys} around every task. The delegate is never closed or owned
     * by the wrapper. {@code ScopedValue.Snapshot} (the JDK's own capture
     * API) is package-private, so propagation is key-explicit by design.
     */
    @SafeVarargs
    public static Executor wrapping(Executor delegate, ScopedValue<?>... keys) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(keys, "keys");
        return task -> {
            Objects.requireNonNull(task, "task");
            // Capture on the submitting thread — this is the whole point.
            Object[] values = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                values[i] = capture(keys[i]);
            }
            delegate.execute(() -> {
                ScopedValue.Carrier carrier = null;
                for (int i = 0; i < keys.length; i++) {
                    if (values[i] == NO_BINDING) {
                        continue;
                    }
                    carrier = carrier == null
                        ? bind(keys[i], values[i])
                        : carrier.where(cast(keys[i]), values[i]);
                }
                if (carrier == null) {
                    task.run();
                } else {
                    carrier.run(task);
                }
            });
        };
    }

    private static final Object NO_BINDING = new Object();

    @SuppressWarnings("unchecked")
    private static <T> ScopedValue<T> cast(ScopedValue<?> key) {
        return (ScopedValue<T>) key;
    }

    private static <T> ScopedValue.Carrier bind(ScopedValue<?> key, Object value) {
        return ScopedValue.where(cast(key), (T) value);
    }

    private static Object capture(ScopedValue<?> key) {
        try {
            return key.get();
        } catch (NoSuchElementException e) {
            return NO_BINDING; // no binding on the submitting thread — skip
        }
    }
}
