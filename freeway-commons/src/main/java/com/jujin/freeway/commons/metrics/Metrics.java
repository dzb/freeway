package com.jujin.freeway.commons.metrics;

/**
 * Minimal observable-counters SPI for framework components.
 *
 * <p>Zero-dependency by design: a counter is a named long accumulator and a
 * gauge is a named sampled value — enough for operational signals
 * (dispatched events, pool borrows, failed subscriptions) without pulling in
 * a metrics library. Implementations are contributed via the IoC container
 * ({@code binder.contribute(Metrics.class)} / primary override); the default
 * is {@link NoopMetrics}, so framework components may call into {@link Metrics}
 * unconditionally.
 *
 * <p>Counters are cumulative since JVM start; consumers (health checks,
 * scrapers) sample deltas.
 */
public interface Metrics {

    /** Returns the named counter, creating it on first access. */
    Counter counter(String name);

    /**
     * A named cumulative counter. Implementations must be thread-safe.
     */
    interface Counter {
        /** Increments by one. */
        void increment();

        /** Adds {@code delta} (may be negative for corrections). */
        void add(long delta);

        /** Current cumulative value. */
        long value();
    }

    /**
     * Registers a named sampled value; {@link #sample()} is invoked by the
     * consumer when it reads the value.
     */
    void gauge(String name, java.util.function.Supplier<Number> value);
}
