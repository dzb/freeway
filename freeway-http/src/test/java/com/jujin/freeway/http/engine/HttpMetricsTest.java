package com.jujin.freeway.http.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.metrics.Metrics;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** HTTP request metrics carry bounded dimensions; the fallback classes are bounded too. */
class HttpMetricsTest {

    @Test
    void methodAndStatusLabelsAreBounded() {
        assertEquals("GET", HttpMetrics.methodLabel("get"));
        assertEquals("OTHER", HttpMetrics.methodLabel("PROPFIND"));
        assertEquals("OTHER", HttpMetrics.methodLabel(null));
        assertEquals("2xx", HttpMetrics.statusClass(204));
        assertEquals("5xx", HttpMetrics.statusClass(503));
        assertEquals("unknown", HttpMetrics.statusClass(0));
        assertEquals("unknown", HttpMetrics.statusClass(600));
    }

    @Test
    void requestMetricsPassTheirLabelsToTheRegistry() {
        LabelMetrics registry = new LabelMetrics();
        HttpMetrics metrics = new HttpMetrics(registry);

        metrics.requestsTotal(Map.of("method", "GET")).increment();
        metrics.requestsDuration(Map.of("method", "GET", "route", "/x"))
            .record(1_000_000);

        assertEquals(1, registry.counters.get("freeway.http.requests.total").size());
        assertEquals(1, registry.histograms.get("freeway.http.requests.duration").size());
        assertEquals(Map.of("method", "GET"),
            registry.counters.get("freeway.http.requests.total").getFirst());
        assertEquals(Map.of("method", "GET", "route", "/x"),
            registry.histograms.get("freeway.http.requests.duration").getFirst());
    }

    private static final class LabelMetrics implements Metrics {
        final Map<String, java.util.List<Map<String, String>>> counters = new HashMap<>();
        final Map<String, java.util.List<Map<String, String>>> timers = new HashMap<>();
        final Map<String, java.util.List<Map<String, String>>> histograms = new HashMap<>();

        @Override
        public Counter counter(String name) {
            return counter(name, Map.of());
        }

        @Override
        public Counter counter(String name, Map<String, String> labels) {
            counters.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(labels);
            return new Counter() {
                @Override public void increment() { }
                @Override public void add(long delta) { }
                @Override public long value() { return 0; }
            };
        }

        @Override
        public Timer timer(String name) {
            return timer(name, Map.of());
        }

        @Override
        public Timer timer(String name, Map<String, String> labels) {
            timers.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(labels);
            return Metrics.NOOP_TIMER;
        }

        @Override
        public Histogram histogram(String name) {
            return histogram(name, Map.of());
        }

        @Override
        public Histogram histogram(String name, Map<String, String> labels) {
            histograms.computeIfAbsent(name, k -> new java.util.ArrayList<>()).add(labels);
            return Metrics.NOOP_HISTOGRAM;
        }

        @Override
        public void gauge(String name, Supplier<Number> value) {
        }
    }
}
