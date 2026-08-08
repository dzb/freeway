package com.jujin.freeway.commons.util;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Thread-safe lazily-initialized value with volatile double-checked reads.
 *
 * <p>The single correct primitive for the framework's lazy-init pattern
 * (a volatile field + synchronized double-check + no rebuild after close):
 * hand-rolled variants have repeatedly drifted into races — readers
 * creating fresh state after a close, structural writes under read locks,
 * or duplicated initialization. Use {@link Lazy} instead of writing another
 * double-check.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #get()} computes exactly once (concurrent callers share the
 *       first result); a throwing supplier propagates and retries on the
 *       next call (failures are not cached).</li>
 *   <li>{@link #peek()} returns the computed value or {@code null} without
 *       computing — the cheap "already built?" probe.</li>
 *   <li>There is intentionally no {@code close()/invalidate()} here:
 *       lifecycles that must reject re-creation after teardown (executors
 *       shut down by {@code close()}) keep their own closed flag and consult
 *       it before calling {@link #get()}.</li>
 * </ul>
 *
 * @param <T> the value type
 */
public final class Lazy<T> {

    private final Supplier<T> supplier;
    private volatile T value;

    private Lazy(Supplier<T> supplier) {
        this.supplier = Objects.requireNonNull(supplier, "supplier");
    }

    /** Creates a lazy value computed from {@code supplier} on first access. */
    public static <T> Lazy<T> of(Supplier<T> supplier) {
        return new Lazy<>(supplier);
    }

    /**
     * Returns the computed value, computing it once on first access.
     * Concurrent callers block only for the first computation; afterwards
     * the read is a volatile load. A throwing supplier propagates and the
     * next call retries.
     */
    public T get() {
        T v = value;
        if (v != null) {
            return v;
        }
        synchronized (this) {
            v = value;
            if (v == null) {
                v = supplier.get();
                if (v == null) {
                    throw new NullPointerException(
                        "Lazy supplier returned null for " + supplier);
                }
                value = v;
            }
            return v;
        }
    }

    /** Returns the computed value without computing, or {@code null}. */
    public T peek() {
        return value;
    }

    /** True when the value has been computed at least once. */
    public boolean isComputed() {
        return value != null;
    }
}
