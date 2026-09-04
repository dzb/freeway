package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.AmbiguousBindingException;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metrics wiring under assembly: the standard {@code CloudModule} must serve
 * {@code /metrics} from the same registry the {@code Metrics} SPI records
 * into (both roles bound to one instance — the snapshot view is never derived
 * from a container proxy by instanceof), and a replacement backend installs
 * instead of the observe module instead of colliding with its primary.
 */
class MetricsAssemblyTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    @Test
    void standardCloudModuleServesMetricsFromTheActiveRegistry() throws Exception {
        try (AppRuntime app = FreewayApp.run(new CloudModule())) {
            app.get(Metrics.class).counter("hits").increment();

            HttpResponse<String> metrics = get(app, "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("# TYPE hits counter\nhits 1"),
                "the /metrics route must export exactly the registry the Metrics SPI records into");
        }
    }

    @Test
    void metricsSnapshotResolvesAndRendersTheModuleRegistry() {
        try (Container container = Freeway.create(new CloudModule())) {
            container.get(Metrics.class).counter("hits").add(2);
            String text = container.get(MetricsSnapshot.class).prometheusText();
            assertTrue(text.contains("hits 2"),
                "the snapshot binding must expose the module registry (one shared instance)");
        }
    }

    @Test
    void replacedBackendServesItsOwnMetricsWithoutAmbiguity() throws Exception {
        try (AppRuntime app = FreewayApp.run(new ExtMetricsBackendModule())) {
            app.get(Metrics.class).counter("hits").add(3);

            HttpResponse<String> metrics = get(app, "/metrics");
            assertEquals(200, metrics.statusCode());
            assertTrue(metrics.body().contains("hits 3"),
                "a backend installed instead of CloudObserveModule must be the active "
                    + "Metrics and export itself");
        }
    }

    @Test
    void coexistingSecondMetricsPrimaryFailsLoudly() {
        try (Container container = Freeway.create(
                new CloudModule(), new SecondMetricsPrimaryModule())) {
            assertThrows(AmbiguousBindingException.class,
                () -> container.get(Metrics.class),
                "two primary Metrics bindings must fail loudly at first resolution — "
                    + "a backend replaces the observe module, it does not join it");
        }
    }

    private static HttpResponse<String> get(AppRuntime app, String path) throws Exception {
        int port = app.get(com.jujin.freeway.http.WebServer.class).port();
        HttpClient client = HttpClient.newHttpClient();
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A second backend trying to install alongside the observe module's own
     *  primary Metrics binding. Contributes no route — the duplicate
     *  {@code /metrics} contribution already fails composition loudly. */
    static final class SecondMetricsPrimaryModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.bind(Metrics.class).to(container -> new FakeBackend()).primary();
        }
    }

    /** A stand-in for a future freeway-ext metrics backend: binds its own
     *  registry as the primary Metrics, exposes its snapshot view, and
     *  contributes its own {@code /metrics} route. */
    static final class ExtMetricsBackendModule implements ModuleEx {

        private final FakeBackend backend = new FakeBackend();

        @Override
        public void bind(Binder b) {
            b.bind(Metrics.class).to(container -> backend).primary();
            b.bind(MetricsSnapshot.class).to(container -> backend);
            b.contribute(Route.class).add("metrics",
                Route.get("/metrics", ctx -> ctx.send(200, backend.prometheusText())));
        }
    }

    /** Minimal self-rendering backend: records counters and renders them as
     *  Prometheus text. */
    static final class FakeBackend implements Metrics, MetricsSnapshot {

        private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

        @Override
        public Counter counter(String name) {
            return new Counter() {
                private final LongAdder adder =
                    counters.computeIfAbsent(name, k -> new LongAdder());

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
            };
        }

        @Override
        public void gauge(String name, Supplier<Number> value) {
            // no-op stand-in
        }

        @Override
        public String prometheusText() {
            StringBuilder sb = new StringBuilder();
            counters.forEach((name, adder) ->
                sb.append("# TYPE ").append(name).append(" counter\n")
                    .append(name).append(' ').append(adder.sum()).append('\n'));
            return sb.toString();
        }
    }
}
