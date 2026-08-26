package com.jujin.freeway.commons.metrics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts of the {@link Metrics} SPI: counter/timer semantics, the timer
 * convenience overloads every implementation inherits, and the no-op
 * defaults framework code relies on when no registry is wired.
 */
class MetricsTest {

    private static final class CountingMetrics implements Metrics {
        final AtomicLong nanos = new AtomicLong();

        @Override public Counter counter(String name) {
            return new Counter() {
                long value;
                @Override public void increment() { value++; }
                @Override public void add(long delta) { value += delta; }
                @Override public long value() { return value; }
            };
        }

        @Override public Timer timer(String name) {
            return new Timer() {
                long count;
                @Override public void record(long nanos) {
                    count++;
                    nanos().addAndGet(nanos);
                }
                @Override public long count() { return count; }
                @Override public long totalNanos() { return nanos.get(); }
            };
        }

        @Override public void gauge(String name, java.util.function.Supplier<Number> value) { }

        AtomicLong nanos() { return nanos; }
    }

    @Test
    void counterAccumulatesIntegerSemantics() {
        Metrics metrics = new CountingMetrics();
        Metrics.Counter c = metrics.counter("events");
        c.increment();
        c.increment();
        c.add(40);
        c.add(-1);
        assertEquals(41, c.value());
    }

    @Test
    void timerDurationOverloadNormalizesToNanos() {
        Metrics metrics = new CountingMetrics();
        Metrics.Timer t = metrics.timer("lat");
        t.record(Duration.ofMillis(250));
        assertEquals(1, t.count());
        assertEquals(250_000_000L, t.totalNanos());
    }

    @Test
    void timerSupplierOverloadRecordsWorkDurationAndReturnsResult() {
        Metrics metrics = new CountingMetrics();
        Metrics.Timer t = metrics.timer("work");
        String result = t.record(() -> {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done";
        });
        assertEquals("done", result);
        assertEquals(1, t.count());
        assertTrue(t.totalNanos() > 0);
    }

    @Test
    void defaultTimerIsNoopAndNoopMetricsStaysAtZero() {
        assertSame(Metrics.NOOP_TIMER, new NoopMetrics().timer("x"));
        assertSame(Metrics.NOOP_TIMER, Metrics.NOOP_TIMER);
        assertEquals(0, Metrics.NOOP_TIMER.count());

        Metrics.Counter noop = NoopMetrics.INSTANCE.counter("n");
        noop.increment();
        noop.add(5);
        assertEquals(0, noop.value());
    }
}
