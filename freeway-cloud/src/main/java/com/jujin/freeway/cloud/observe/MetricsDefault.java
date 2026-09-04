package com.jujin.freeway.cloud.observe;

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
public final class MetricsDefault implements Metrics, MetricsSnapshot {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, TimerData> timers = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Number>> gauges = new ConcurrentHashMap<>();
    /** Final rendered Prometheus series name → "kind:rawName" owner token.
     *  Tracked on the rendered name so timer-suffixed series
     *  ({@code _count}/{@code _seconds_total}) collide with plain meters too. */
    private final Map<String, String> renderedSeries = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name) {
        registerSeriesName(renderedSeries, name, "counter", prometheusName(name));
        return new CounterImpl(counters.computeIfAbsent(name, k -> new LongAdder()));
    }

    @Override
    public Timer timer(String name) {
        String rendered = prometheusName(name);
        registerSeriesName(renderedSeries, name, "timer",
            rendered + "_count", rendered + "_seconds_total");
        return new TimerImpl(timers.computeIfAbsent(name, k -> new TimerData()));
    }

    @Override
    public void gauge(String name, Supplier<Number> supplier) {
        registerSeriesName(renderedSeries, name, "gauge", prometheusName(name));
        gauges.put(name, supplier);
    }

    /**
     * Fails fast when a registration would emit a Prometheus series another
     * metric already occupies. {@code prometheusName} folds {@code a.b} and
     * {@code a-b} onto one series; timers additionally occupy the suffixed
     * {@code _count}/{@code _seconds_total} series, so those are registered
     * here too. Duplicate samples make the scraper reject the whole
     * exposition. Re-registering the exact same kind+name is fine (a gauge
     * refresh).
     */
    private static void registerSeriesName(
        Map<String, String> series,
        String rawName,
        String kind,
        String... renderedNames
    ) {
        String token = kind + ":" + rawName;
        for (int i = 0; i < renderedNames.length; i++) {
            String rendered = renderedNames[i];
            String previous = series.putIfAbsent(rendered, token);
            if (previous != null && !previous.equals(token)) {
                // A multi-series registration (timer) must not leave its
                // earlier suffix claims behind when a later one collides.
                for (int j = 0; j < i; j++) {
                    series.remove(renderedNames[j], token);
                }
                throw new IllegalArgumentException(
                    "Metric name '" + rawName + "' (" + kind + ") renders as the same "
                        + "Prometheus series '" + rendered + "' as the earlier registration '"
                        + previous.substring(previous.indexOf(':') + 1)
                        + "' — rename one of them");
            }
        }
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

    private static final class CounterImpl implements Counter {
        private final LongAdder adder;

        CounterImpl(LongAdder adder) {
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

    private static final class TimerImpl implements Timer {
        private final TimerData data;

        TimerImpl(TimerData data) {
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
