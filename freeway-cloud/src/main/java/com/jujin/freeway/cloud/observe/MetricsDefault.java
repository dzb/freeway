package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.commons.metrics.Metrics;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * In-memory {@link Metrics}: counters (LongAdder), timers (count + total
 * nanos), gauges (read on demand). Snapshots feed the Prometheus-text
 * {@code /metrics} route.
 *
 * <p>Names are the metric's identity; labels are bounded dimensions on that
 * identity. An unlabeled read ({@link #counter(String)}) and a labeled write
 * ({@link #counter(String, Map)}) are different series, so a backend that
 * ignores labels still records its aggregate while this registry keeps the
 * dimensions apart.
 */
public final class MetricsDefault implements Metrics, MetricsSnapshot {

    private static final int MAX_LABELS = 16;

    private final Map<Key, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<Key, TimerData> timers = new ConcurrentHashMap<>();
    private final Map<Key, Supplier<Number>> gauges = new ConcurrentHashMap<>();
    /** Final rendered Prometheus series name → "kind:rawName{labels}" owner
     *  token. Tracked on the rendered name so timer-suffixed series
     *  ({@code _count}/{@code _seconds_total}) collide with plain meters too. */
    private final Map<String, String> renderedSeries = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name) {
        return counter(name, Map.of());
    }

    @Override
    public Counter counter(String name, Map<String, String> labels) {
        Key key = key(name, labels);
        registerSeriesName(renderedSeries, key, "counter",
            prometheusName(key.name()) + renderLabels(key.labels()));
        return new CounterImpl(counters.computeIfAbsent(key, k -> new LongAdder()));
    }

    @Override
    public Timer timer(String name) {
        return timer(name, Map.of());
    }

    @Override
    public Timer timer(String name, Map<String, String> labels) {
        Key key = key(name, labels);
        String base = prometheusName(key.name());
        String renderedLabels = renderLabels(key.labels());
        registerSeriesName(renderedSeries, key, "timer",
            base + "_count" + renderedLabels,
            base + "_seconds_total" + renderedLabels);
        return new TimerImpl(timers.computeIfAbsent(key, k -> new TimerData()));
    }

    @Override
    public void gauge(String name, Supplier<Number> supplier) {
        gauge(name, Map.of(), supplier);
    }

    @Override
    public void gauge(String name, Map<String, String> labels, Supplier<Number> supplier) {
        Key key = key(name, labels);
        registerSeriesName(renderedSeries, key, "gauge",
            prometheusName(key.name()) + renderLabels(key.labels()));
        gauges.put(key, supplier);
    }

    /**
     * Fails fast when a registration would emit a Prometheus series another
     * metric already occupies. {@code prometheusName} folds {@code a.b} and
     * {@code a-b} onto one series; timers additionally occupy the suffixed
     * {@code _count}/{@code _seconds_total} series, so those are registered
     * here too. Duplicate samples make the scraper reject the whole
     * exposition. Re-registering the exact same kind+name+labels is fine (a
     * gauge refresh).
     */
    private static void registerSeriesName(
        Map<String, String> series,
        Key key,
        String kind,
        String... renderedNames
    ) {
        String token = kind + ":" + key.name() + renderLabels(key.labels());
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
                    "Metric name '" + key.name() + "' (" + kind + ") renders as the same "
                        + "Prometheus series '" + rendered + "' as the earlier registration '"
                        + previous.substring(previous.indexOf(':') + 1)
                        + "' — rename one of them");
            }
        }
    }

    // ── Single-value accessors (test/verification surface, not SPI) ──────
    // The /metrics route renders whole registries through prometheusText();
    // these read one meter by name (and optional labels) for assertions, so
    // they stay on the concrete class — neither the Metrics SPI nor the
    // MetricsSnapshot view exposes them.

    public long counterValue(String name) {
        return counterValue(name, Map.of());
    }

    public long counterValue(String name, Map<String, String> labels) {
        LongAdder adder = counters.get(key(name, labels));
        return adder == null ? 0L : adder.sum();
    }

    public long timerCount(String name) {
        return timerCount(name, Map.of());
    }

    public long timerCount(String name, Map<String, String> labels) {
        TimerData data = timers.get(key(name, labels));
        return data == null ? 0 : data.count.sum();
    }

    public double timerTotalSeconds(String name) {
        return timerTotalSeconds(name, Map.of());
    }

    public double timerTotalSeconds(String name, Map<String, String> labels) {
        TimerData data = timers.get(key(name, labels));
        return data == null ? 0.0 : data.totalNanos.sum() / 1_000_000_000.0;
    }

    public double gaugeValue(String name) {
        return gaugeValue(name, Map.of());
    }

    public double gaugeValue(String name, Map<String, String> labels) {
        Supplier<Number> supplier = gauges.get(key(name, labels));
        Number value = supplier == null ? null : supplier.get();
        return value == null ? 0.0 : value.doubleValue();
    }

    /** Renders the registry as Prometheus text format ({@code /metrics} route). */
    public String prometheusText() {
        StringBuilder sb = new StringBuilder();

        // One TYPE line per rendered series name, even when several label sets
        // share it; a scrape rejects a metric whose type flips between series.
        Map<String, String> types = new TreeMap<>();
        counters.keySet().forEach(key ->
            types.put(prometheusName(key.name()), "counter"));
        timers.keySet().forEach(key -> {
            String base = prometheusName(key.name());
            types.put(base + "_count", "counter");
            types.put(base + "_seconds_total", "counter");
        });
        gauges.keySet().forEach(key ->
            types.put(prometheusName(key.name()), "gauge"));
        types.forEach((name, type) ->
            sb.append("# TYPE ").append(name).append(' ').append(type).append('\n'));

        counters.entrySet().stream()
            .sorted(byKey())
            .forEach(entry -> sb.append(renderCounter(entry.getKey(), entry.getValue())));
        timers.entrySet().stream()
            .sorted(byKey())
            .forEach(entry -> sb.append(renderTimer(entry.getKey(), entry.getValue())));
        gauges.entrySet().stream()
            .sorted(byKey())
            .forEach(entry -> sb.append(renderGauge(entry.getKey(), entry.getValue())));
        return sb.toString();
    }

    private static Comparator<Map.Entry<Key, ?>> byKey() {
        return Comparator
            .comparing((Map.Entry<Key, ?> entry) -> entry.getKey().name())
            .thenComparing(entry -> renderLabels(entry.getKey().labels()));
    }

    private static String renderCounter(Key key, LongAdder adder) {
        return prometheusName(key.name()) + renderLabels(key.labels())
            + " " + adder.sum() + "\n";
    }

    private static String renderTimer(Key key, TimerData data) {
        String name = prometheusName(key.name());
        String labels = renderLabels(key.labels());
        return name + "_count" + labels + " " + data.count() + "\n"
            + name + "_seconds_total" + labels + " "
            + Double.toString(data.totalNanos() / 1_000_000_000.0) + "\n";
    }

    private static String renderGauge(Key key, Supplier<Number> supplier) {
        double value;
        try {
            Number number = supplier.get();
            value = number == null ? Double.NaN : number.doubleValue();
        } catch (RuntimeException ex) {
            value = Double.NaN; // a broken gauge must not 500 the /metrics route
        }
        return prometheusName(key.name()) + renderLabels(key.labels())
            + " " + value + "\n";
    }

    /** Sanitizes a metric name to Prometheus name rules
     *  ([a-zA-Z0-9_:]), preventing format-breaking characters (spaces,
     *  newlines) from reaching the text exposition. */
    private static String prometheusName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9_:]", "_");
        if (sanitized.isEmpty()) {
            return "_";
        }
        char first = sanitized.charAt(0);
        // The exposition grammar requires [a-zA-Z_:] first; a scrape rejects
        // the whole payload over one such name.
        return first >= '0' && first <= '9' ? "_" + sanitized : sanitized;
    }

    /** Renders a sorted label set, escaped for the Prometheus text format. */
    private static String renderLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("{");
        labels.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                if (sb.length() > 1) {
                    sb.append(',');
                }
                sb.append(prometheusLabelName(entry.getKey())).append("=\"")
                    .append(escapeLabelValue(entry.getValue())).append('"');
            });
        return sb.append('}').toString();
    }

    private static String prometheusLabelName(String name) {
        String sanitized = name.replaceAll("[^a-zA-Z0-9_]", "_");
        if (sanitized.isEmpty()) {
            return "_";
        }
        char first = sanitized.charAt(0);
        return first >= '0' && first <= '9' ? "_" + sanitized : sanitized;
    }

    private static String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\"", "\\\"");
    }

    private static Key key(String name, Map<String, String> labels) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("metric name must not be blank");
        }
        Map<String, String> copied = labels == null || labels.isEmpty()
            ? Map.of()
            : Map.copyOf(labels);
        if (copied.size() > MAX_LABELS) {
            throw new IllegalArgumentException(
                "metric " + name + " has " + copied.size() + " labels; at most " + MAX_LABELS
                    + " bounded dimensions are supported");
        }
        for (Map.Entry<String, String> entry : copied.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "label name");
            Objects.requireNonNull(entry.getValue(), "label value");
            if (entry.getKey().isBlank()) {
                throw new IllegalArgumentException("metric label name must not be blank");
            }
            if (entry.getValue().isEmpty()) {
                throw new IllegalArgumentException(
                    "metric label '" + entry.getKey() + "' must not have an empty value");
            }
        }
        return new Key(name, copied);
    }

    private record Key(String name, Map<String, String> labels) {
        Key {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }
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

    private static final class TimerData {
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
