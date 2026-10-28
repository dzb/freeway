package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.observe.CloudObserveModule;
import com.jujin.freeway.http.HttpModule;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The plane's counters are on the scrape surface when observability is
 * installed — and they are there before any peer exists, because "no peers" is
 * a fact a dashboard must be able to see, not a missing series.
 *
 * <p>Names are the raw meter names ({@code cloud.event.*}); the registry folds
 * them onto Prometheus series, so the assertions read the rendered form.</p>
 */
class CloudEventMetricsTest {

    private AppRuntime app;

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpModule.ConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void cleanup() {
        if (app != null) {
            app.close();
        }
        System.clearProperty(HttpModule.ConfigKeys.SERVER_PORT);
        System.clearProperty("freeway.cloud.event.enabled");
        System.clearProperty("freeway.cloud.event.peers");
    }

    private String scrape() throws Exception {
        int port = app.get(com.jujin.freeway.http.HttpServer.class).port();
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/metrics")).GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    @Test
    void the_planes_counters_are_scrapable_before_the_mesh_exists() throws Exception {
        // No peers, no enabled key: the mesh stays unwired on purpose.
        app = FreewayApp.run(new String[0],
            new HttpModule(), new CloudObserveModule(), new CloudEventModule());

        String metrics = scrape();

        assertTrue(metrics.contains("cloud_event_published"),
            "published is a fact about this node, wired or not:\n" + metrics);
        assertTrue(metrics.contains("cloud_event_frames_queued"),
            "the queue counters must be on the scrape surface:\n" + metrics);
        assertTrue(metrics.contains("cloud_event_frames_dropped"),
            "drops are the series that explains a silent peer:\n" + metrics);
        assertTrue(metrics.contains("cloud_event_peers"),
            "peer count says whether the mesh is wired at all:\n" + metrics);
    }

    @Test
    void publishing_moves_the_counters_the_scrape_reads() throws Exception {
        app = FreewayApp.run(new String[0],
            new HttpModule(), new CloudObserveModule(), new CloudEventModule());

        app.get(CloudEventBus.class).publish("metrics.tick", "one");

        // Gauges are read on demand, so the next scrape is the new value.
        String metrics = scrape();
        assertTrue(metrics.contains("cloud_event_published 1"),
            "the published gauge must track the plane's counter:\n" + metrics);
    }
}
