package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.commons.metrics.Metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * In-memory {@link Metrics}: counters (LongAdder), timers (count + total
 * nanos), gauges (read on demand). Snapshots feed the Prometheus-text
 * {@code /metrics} route.
 */
public final class MetricsDefault implements Metrics {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, TimerData> timers = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Number>> gauges = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name) {
        return new DefaultCounter(counters.computeIfAbsent(name, k -> new LongAdder()));
    }

    @Override
    public Timer timer(String name) {
        return new DefaultTimer(timers.computeIfAbsent(name, k -> new TimerData()));
    }

    @Override
    public void gauge(String name, Supplier<Number> supplier) {
        gauges.put(name, supplier);
    }

    // ── Snapshot accessors (for /metrics export) ─────────────

    public long counterValue(String name) {
        LongAdder adder = counters.get(name);
        return adder == null ? 0L : adder.sum();
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
        Supplier<Number> supplier = gauges.get(name);
        Number value = supplier == null ? null : supplier.get();
        return value == null ? 0.0 : value.doubleValue();
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
                Number number = supplier.get();
                value = number == null ? Double.NaN : number.doubleValue();
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
        private final LongAdder adder;

        DefaultCounter(LongAdder adder) {
            this.adder = adder;
        }

        @Override
        public void increment() {
            adder.increment();
        }

        @Override
        public void add(long delta) {
            adder.add(delta);
        }

        @Override
        public long value() {
            return adder.sum();
        }
    }

    private static final class DefaultTimer implements Timer {
        private final TimerData data;

        DefaultTimer(TimerData data) {
            this.data = data;
        }

        @Override
        public void record(long nanos) {
            data.count.increment();
            data.totalNanos.add(nanos);
        }

        @Override
        public long count() {
            return data.count();
        }

        @Override
        public long totalNanos() {
            return data.totalNanos();
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
