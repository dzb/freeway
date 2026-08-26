package com.jujin.freeway.commons.metrics;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * The framework-wide observability SPI: named counters, timers, and gauges.
 *
 * <p>Zero-dependency by design — enough for operational signals (dispatched
 * events, request durations, pool depth) without pulling in a metrics
 * library. Every framework component instruments through this one interface;
 * richer backends (Prometheus text export, OTLP) implement it rather than
 * introducing a parallel registry. Implementations are wired via the IoC
 * container ({@code bind(Metrics.class)...primary()} to override the
 * {@link NoopMetrics} builtin); framework components may call into
 * {@link Metrics} unconditionally.
 *
 * <p>Counters are cumulative since JVM start; consumers (health checks,
 * scrapers) sample deltas.
 */
public interface Metrics {

    /** Returns the named counter, creating it on first access. */
    Counter counter(String name);

    /** Returns the named duration timer, creating it on first access.
     *  Defaults to a no-op so existing implementations keep working. */
    default Timer timer(String name) {
        return NOOP_TIMER;
    }

    /**
     * A named cumulative counter with integer semantics. Implementations
     * must be thread-safe.
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
     * thread-safe; only {@link #record(long)} is abstract — nanoseconds are
     * the canonical unit, the other overloads normalize onto it.
     */
    interface Timer {
        void record(long nanos);

        /** Records a duration. */
        default void record(Duration duration) {
            record(duration.toNanos());
        }

        /** Records the work's duration and returns its result. */
        default <T> T record(Supplier<T> work) {
            long start = System.nanoTime();
            try {
                return work.get();
            } finally {
                record(System.nanoTime() - start);
            }
        }

        long count();

        long totalNanos();
    }

    Timer NOOP_TIMER = new Timer() {
        @Override public void record(long nanos) { }
        @Override public long count() { return 0L; }
        @Override public long totalNanos() { return 0L; }
    };

    /**
     * Registers a named sampled value; the supplier is invoked when the
     * value is read (e.g. pool depth, queue size).
     */
    void gauge(String name, Supplier<Number> value);
}
