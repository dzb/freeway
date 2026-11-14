package com.jujin.freeway.http.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpPipeline;
import com.jujin.freeway.http.HttpServer;
import com.jujin.freeway.http.TestHttp;
import com.jujin.freeway.http.TestServerConfig;
import com.jujin.freeway.http.route.Route;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Route labels use the declared template, never the raw request path: a
 * parameterised route reports one stable series, and a miss reports
 * {@code unmatched} rather than the attacker-controlled path.
 */
class HttpRouteMetricTest {

    @Test
    void requestMetricsCarryTheDeclaredRouteTemplate() throws Exception {
        LabelMetrics metrics = new LabelMetrics();
        HttpServer server = HttpServer.create(
            TestHttp.engine(metrics),
            TestServerConfig.loopback(),
            HttpPipeline.of(
                Route.get("/users/{id}", ctx -> ctx.sendText(200, "user")),
                Route.post("/users", ctx -> ctx.sendText(201, "created"))));
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).build();
            send(client, server, "GET", "/users/42", 200);
            send(client, server, "POST", "/users", 201);
            send(client, server, "GET", "/users/42/extra", 404);
            send(client, server, "GET", "/users", 405);

            await(() -> metrics.counterLabels("freeway.http.requests.total").contains(
                    Map.of("method", "GET", "route", "/users/{id}")),
                "the parameterised GET must report its template, not /users/42");
            assertTrue(metrics.counterLabels("freeway.http.requests.total").contains(
                    Map.of("method", "POST", "route", "/users")),
                "a literal route reports the literal template");
            assertTrue(metrics.counterLabels("freeway.http.requests.total").contains(
                    Map.of("method", "GET", "route", "unmatched")),
                "a miss is one bounded category, never the raw path");
            assertTrue(metrics.histogramLabels("freeway.http.requests.duration").contains(
                    Map.of("method", "GET", "route", "/users/{id}")),
                "the duration histogram carries method + route (status stays on the counters)");
            assertTrue(metrics.counterLabels("freeway.http.responses.4xx").contains(
                    Map.of("method", "GET", "route", "unmatched")),
                "the 4xx counter carries the same route category; 405 is one bounded category too");
        } finally {
            server.stop();
        }
    }

    private static void send(HttpClient client, HttpServer server, String method,
            String path, int expected) throws Exception {
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() == expected,
            method + " " + path + " -> " + response.statusCode());
    }

    private static void await(BooleanSupplier condition, String message) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(message);
    }

    /** Records the label dimensions the server passes, one key per sample. */
    private static final class LabelMetrics implements Metrics {
        private final Map<String, List<Map<String, String>>> counters =
            new ConcurrentHashMap<>();
        private final Map<String, List<Map<String, String>>> timers =
            new ConcurrentHashMap<>();
        private final Map<String, List<Map<String, String>>> histograms =
            new ConcurrentHashMap<>();

        Set<Map<String, String>> counterLabels(String name) {
            return Set.copyOf(counters.getOrDefault(name, List.of()));
        }

        Set<Map<String, String>> timerLabels(String name) {
            return Set.copyOf(timers.getOrDefault(name, List.of()));
        }

        Set<Map<String, String>> histogramLabels(String name) {
            return Set.copyOf(histograms.getOrDefault(name, List.of()));
        }

        @Override
        public Counter counter(String name) {
            return counter(name, Map.of());
        }

        @Override
        public Counter counter(String name, Map<String, String> labels) {
            counters.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(labels);
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
            timers.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(labels);
            return Metrics.NOOP_TIMER;
        }

        @Override
        public Histogram histogram(String name) {
            return histogram(name, Map.of());
        }

        @Override
        public Histogram histogram(String name, Map<String, String> labels) {
            histograms.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(labels);
            return Metrics.NOOP_HISTOGRAM;
        }

        @Override
        public void gauge(String name, Supplier<Number> value) {
        }
    }
}
