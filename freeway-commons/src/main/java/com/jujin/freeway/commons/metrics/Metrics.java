package com.jujin.freeway.commons.metrics;
import java.util.function.Supplier;

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

    /** Returns the named duration timer (nanoseconds), creating it on first
     *  access. Defaults to a no-op so existing implementations keep working. */
    default Timer timer(String name) {
        return NOOP_TIMER;
    }

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
     * A named cumulative duration recorder. Implementations must be
     * thread-safe. {@link #record(long)} takes a duration in nanoseconds.
     */
    interface Timer {
        void record(long nanos);

        long count();

        long totalNanos();
    }

    Timer NOOP_TIMER = new Timer() {
        @Override public void record(long nanos) { }
        @Override public long count() { return 0L; }
        @Override public long totalNanos() { return 0L; }
    };

    /**
     * Registers a named sampled value; {@link #sample()} is invoked by the
     * consumer when it reads the value.
     */
    void gauge(String name, Supplier<Number> value);
}
