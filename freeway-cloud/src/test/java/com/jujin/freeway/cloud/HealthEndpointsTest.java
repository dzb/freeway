package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.health.HealthResult;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cloud-native probes: {@code /health/live} (process liveness) and
 * {@code /health/ready} (aggregated dependency readiness, 503 when unhealthy).
 */
class HealthEndpointsTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    @Test
    void liveAndReadyAreOkByDefault() throws Exception {
        try (AppRuntime app = FreewayApp.run(new HttpModule(), new CloudModule())) {
            HttpResponse<String> live = get(app, "/health/live");
            assertEquals(200, live.statusCode());
            assertTrue(live.body().contains("\"ok\""));

            HttpResponse<String> ready = get(app, "/health/ready");
            assertEquals(200, ready.statusCode());
            assertTrue(ready.body().contains("\"ok\""));
            assertTrue(ready.body().contains("\"cloud\""));
            assertTrue(ready.body().contains("registry"),
                "the built-in registry contributor must be aggregated");
        }
    }

    @Test
    void readyFailsWhenAContributorIsUnhealthy() throws Exception {
        try (AppRuntime app = FreewayApp.run(new FailingContributorModule(), new HttpModule(), new CloudModule())) {
            HttpResponse<String> ready = get(app, "/health/ready");
            assertEquals(503, ready.statusCode());
            assertTrue(ready.body().contains("unhealthy"));
            assertTrue(ready.body().contains("registry-unreachable"));
        }
    }

    private static HttpResponse<String> get(AppRuntime app, String path) throws Exception {
        int port = app.get(com.jujin.freeway.http.WebServer.class).port();
        HttpClient client = HttpClient.newHttpClient();
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static class FailingContributorModule implements ModuleEx {
        @Override
        public void bind(Binder b) {
            b.contribute(CloudHealthContributor.class).add("fake-registry", new CloudHealthContributor() {
                @Override
                public String name() {
                    return "fake-registry";
                }

                @Override
                public HealthResult check() {
                    return HealthResult.unhealthy("registry-unreachable");
                }
            });
        }
    }
}
