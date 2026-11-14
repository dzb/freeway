package com.jujin.freeway.commons.metrics;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The framework-wide observability SPI: named counters, timers, and gauges.
 *
 * <p>Zero-dependency by design — enough for operational signals (dispatched
 * event, request durations, pool depth) without pulling in a metrics
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

    /**
     * Returns a counter distinguished by bounded labels (method, status class,
     * service, ...). The default delegates to {@link #counter(String)} and
     * ignores the labels: a backend that does not model dimensions still
     * records the aggregate, and a caller never has to branch on backend type.
     * The framework's default registry ({@code MetricsDefault}) implements
     * them.
     *
     * <p>Labels are dimensions, not names: use a small fixed vocabulary and
     * never raw paths, URLs, ids or user input as a value. The aggregate name
     * is unchanged, so an unlabeled reader still sees the same series.
     */
    default Counter counter(String name, Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels");
        return counter(name);
    }

    /** Returns the named duration timer, creating it on first access.
     *  Defaults to a no-op so existing implementations keep working. */
    default Timer timer(String name) {
        return NOOP_TIMER;
    }

    /** Labeled timer; the default delegates to {@link #timer(String)}. */
    default Timer timer(String name, Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels");
        return timer(name);
    }

    /**
     * Fixed latency bucket upper bounds in nanoseconds. The set is deliberately
     * small and framework-owned: five milliseconds to ten seconds, plus the
     * implicit {@code +Inf} bucket. A backend maps these to its own histogram
     * model; a caller never supplies arbitrary buckets.
     */
    List<Long> DEFAULT_LATENCY_BUCKETS_NANOS = List.of(
        5_000_000L, 10_000_000L, 25_000_000L, 50_000_000L,
        100_000_000L, 250_000_000L, 500_000_000L,
        1_000_000_000L, 2_500_000_000L, 5_000_000_000L, 10_000_000_000L);

    /** Returns the default histogram (see {@link #DEFAULT_LATENCY_BUCKETS_NANOS}). */
    default Histogram histogram(String name) {
        return histogram(name, Map.of());
    }

    /**
     * Returns a histogram for tail-latency observation. The default delegates
     * to {@link #timer(String, Map)}, so a backend without histogram support
     * still records the aggregate count/sum; a caller never has to branch on
     * backend type. The framework's default registry renders the buckets and
     * implements quantile queries.
     */
    default Histogram histogram(String name, Map<String, String> labels) {
        Objects.requireNonNull(labels, "labels");
        Timer timer = timer(name, labels);
        return new Histogram() {
            @Override
            public void record(long nanos) {
                timer.record(nanos);
            }

            @Override
            public long count() {
                return timer.count();
            }

            @Override
            public long totalNanos() {
                return timer.totalNanos();
            }
        };
    }

    /**
     * A latency distribution with fixed buckets. Implementations must be
     * thread-safe. {@link #record(long)} is the only abstract method; the
     * convenience overloads normalize to nanoseconds.
     */
    interface Histogram {
        void record(long nanos);

        default void record(Duration duration) {
            record(duration.toNanos());
        }

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

    Histogram NOOP_HISTOGRAM = new Histogram() {
        @Override public void record(long nanos) { }
        @Override public long count() { return 0L; }
        @Override public long totalNanos() { return 0L; }
    };

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

    /** Labeled gauge; the default delegates to {@link #gauge(String, Supplier)}. */
    default void gauge(String name, Map<String, String> labels, Supplier<Number> value) {
        Objects.requireNonNull(labels, "labels");
        gauge(name, value);
    }
}
