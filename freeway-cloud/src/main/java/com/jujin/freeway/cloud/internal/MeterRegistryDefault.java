package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.observe.MeterRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * In-memory {@link MeterRegistry}: counters (DoubleAdder), timers
 * (count + total nanos), gauges (read on demand). Snapshots feed the
 * Prometheus-text {@code /metrics} route (Phase 4).
 */
public final class MeterRegistryDefault implements MeterRegistry {

    private final Map<String, DoubleAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, TimerData> timers = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Double>> gauges = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name) {
        return new DefaultCounter(counters.computeIfAbsent(name, k -> new DoubleAdder()));
    }

    @Override
    public Timer timer(String name) {
        return new DefaultTimer(timers.computeIfAbsent(name, k -> new TimerData()));
    }

    @Override
    public void gauge(String name, Supplier<Double> supplier) {
        gauges.put(name, supplier);
    }

    // ── Snapshot accessors (for /metrics export) ─────────────

    public double counterValue(String name) {
        DoubleAdder adder = counters.get(name);
        return adder == null ? 0.0 : adder.sum();
    }

    public long timerCount(String name) {
        TimerData data = timers.get(name);
        return data == null ? 0 : data.count.sum();
    }

    public double timerTotalSeconds(String name) {
        TimerData data = timers.get(name);
        return data == null ? 0.0 : data.totalNanos.sum() / 1_000_000_000.0;
    }

    public double gaugeValue(String name) {
        Supplier<Double> supplier = gauges.get(name);
        return supplier == null ? 0.0 : supplier.get();
    }

    public Map<String, DoubleAdder> counters() {
        return counters;
    }

    public Map<String, TimerData> timers() {
        return timers;
    }

    public Map<String, Supplier<Double>> gauges() {
        return gauges;
    }

    /** Renders the registry as Prometheus text format ({@code /metrics} route). */
    public String prometheusText() {
        StringBuilder sb = new StringBuilder();
        counters.forEach((name, adder) -> {
            sb.append("# TYPE ").append(prometheusName(name)).append(" counter\n");
            sb.append(prometheusName(name)).append(' ').append(adder.sum()).append('\n');
        });
        timers.forEach((name, data) -> {
            sb.append("# TYPE ").append(prometheusName(name)).append("_count counter\n");
            sb.append(prometheusName(name)).append("_count ").append(data.count()).append('\n');
            sb.append("# TYPE ").append(prometheusName(name)).append("_seconds_total counter\n");
            sb.append(prometheusName(name)).append("_seconds_total ")
                .append(Double.toString(data.totalNanos() / 1_000_000_000.0)).append('\n');
        });
        gauges.forEach((name, supplier) -> {
            sb.append("# TYPE ").append(prometheusName(name)).append(" gauge\n");
            double value;
            try {
                value = supplier.get();
            } catch (RuntimeException ex) {
                value = Double.NaN; // a broken gauge must not 500 the /metrics route
            }
            sb.append(prometheusName(name)).append(' ').append(value).append('\n');
        });
        return sb.toString();
    }

    /** Sanitizes a metric name to Prometheus label-name rules
     *  ([a-zA-Z0-9_:]), preventing format-breaking characters (spaces,
     *  newlines) from reaching the text exposition. */
    private static String prometheusName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_:]", "_");
    }

    private static final class DefaultCounter implements Counter {
        private final DoubleAdder adder;

        DefaultCounter(DoubleAdder adder) {
            this.adder = adder;
        }

        @Override
        public void increment() {
            adder.add(1.0);
        }

        @Override
        public void increment(double amount) {
            if (amount < 0) {
                throw new IllegalArgumentException(
                    "counter increment must not be negative: " + amount);
            }
            adder.add(amount);
        }
    }

    private static final class DefaultTimer implements Timer {
        private final TimerData data;

        DefaultTimer(TimerData data) {
            this.data = data;
        }

        @Override
        public void record(Duration duration) {
            data.count.increment();
            data.totalNanos.add(duration.toNanos());
        }
    }

    public static final class TimerData {
        private final LongAdder count = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();

        public long count() {
            return count.sum();
        }

        public long totalNanos() {
            return totalNanos.sum();
        }
    }
}
