package com.jujin.freeway.cloud.observe;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Metrics registry: counters, timers, gauges. In-memory default; exported as
 * Prometheus text via the {@code /metrics} route (Phase 4).
 */
public interface MeterRegistry {

    Counter counter(String name);

    Timer timer(String name);

    /** Registers a gauge read on demand (e.g. pool depth, queue size). */
    void gauge(String name, Supplier<Double> supplier);

    interface Counter {
        void increment();

        void increment(double amount);
    }

    interface Timer {
        void record(Duration duration);

        /** Records the work's duration and returns its result. */
        default <T> T record(Supplier<T> work) {
            long start = System.nanoTime();
            try {
                return work.get();
            } finally {
                record(Duration.ofNanos(System.nanoTime() - start));
            }
        }
    }
}
