package com.jujin.freeway.commons.metrics;
import java.util.function.Supplier;

/**
 * No-op {@link Metrics}: all counters stay at zero, gauges are ignored.
 * The default implementation wired into the container — instrumentation is
 * always callable and costs nothing until an implementation is contributed.
 */
public final class NoopMetrics implements Metrics {

    /** Shared instance for the default container wiring. */
    public static final NoopMetrics INSTANCE = new NoopMetrics();

    private static final Counter NOOP_COUNTER = new Counter() {
        @Override public void increment() { }
        @Override public void add(long delta) { }
        @Override public long value() { return 0L; }
    };

    @Override
    public Counter counter(String name) {
        return NOOP_COUNTER;
    }

    @Override
    public void gauge(String name, Supplier<Number> value) {
        // ignored
    }
}
