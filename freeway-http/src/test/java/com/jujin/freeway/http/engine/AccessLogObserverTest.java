package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.HttpPipeline;
import com.jujin.freeway.http.HttpServer;
import com.jujin.freeway.http.TestHttp;
import com.jujin.freeway.http.TestServerConfig;
import com.jujin.freeway.http.event.AccessLog;
import com.jujin.freeway.http.event.HttpExchangeEvent;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.route.Route;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The access log is an observer of {@link HttpExchangeEvent}, not a filter.
 * The server publishes outside the filter chain, so a line exists for requests
 * a filter never sees — preflight and health probes — and the event now carries
 * the peer and user agent the log formats.
 */
class AccessLogObserverTest {

    private static HttpResponse<String> get(int port, String path, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + port + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newBuilder().build().send(request.build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theEventCarriesPeerAgentAndStatus() throws Exception {
        List<HttpExchangeEvent> events = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(TestHttp.engine(), TestServerConfig.loopback(),
            HttpPipeline.of(Route.get("/ok", ctx -> ctx.send(200, "ok"))),
            event -> {
                if (event instanceof HttpExchangeEvent exchange) {
                    events.add(exchange);
                }
            });
        server.start();
        try {
            get(server.port(), "/ok", "User-Agent", "freeway-observer-test");
            await(() -> !events.isEmpty());
        } finally {
            server.stop();
        }

        HttpExchangeEvent event = events.getFirst();
        assertEquals("GET", event.method());
        assertEquals("/ok", event.path());
        assertEquals(200, event.statusCode());
        assertEquals("127.0.0.1", event.remoteAddress());
        assertEquals("freeway-observer-test", event.userAgent());
    }

    @Test
    void aHealthProbeProducesAnEventEvenThoughItNeverReachesTheRoute() throws Exception {
        List<HttpExchangeEvent> events = new CopyOnWriteArrayList<>();
        var server = HttpServer.create(TestHttp.engine(), TestServerConfig.loopback(),
            // Default health filter answers /healthz before dispatch.
            HttpPipeline.of(Route.get("/ok", ctx -> ctx.send(200, "ok"))),
            event -> {
                if (event instanceof HttpExchangeEvent exchange) {
                    events.add(exchange);
                }
            });
        server.start();
        try {
            get(server.port(), "/healthz");
            await(() -> !events.isEmpty());
        } finally {
            server.stop();
        }

        HttpExchangeEvent event = events.getFirst();
        assertEquals("/healthz", event.path(),
            "the probe is answered by a filter, but the server still publishes it");
        assertEquals(200, event.statusCode());
    }

    @Test
    void aCorsPreflightProducesAnEventAndALogLine() throws Exception {
        List<HttpExchangeEvent> events = new CopyOnWriteArrayList<>();
        var log = new ByteArrayOutputStream();
        AccessLog accessLog = new AccessLog(new PrintStream(log, true, StandardCharsets.UTF_8));
        CorsFilter cors = CorsFilter.defaults()
            .withEnabled(true)
            .withAllowedOrigins(List.of("http://example.test"));
        var server = HttpServer.create(TestHttp.engine(), TestServerConfig.loopback(),
            HttpPipeline.of(Route.get("/ok", ctx -> ctx.send(200, "ok"))).withCors(cors),
            event -> {
                if (event instanceof HttpExchangeEvent exchange) {
                    events.add(exchange);
                    accessLog.accept(exchange);
                }
            });
        server.start();
        try {
            HttpResponse<String> response = HttpClient.newBuilder().build().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/ok"))
                    .header("Origin", "http://example.test")
                    .header("Access-Control-Request-Method", "GET")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(204, response.statusCode(), "the preflight is answered by CORS");
            await(() -> !events.isEmpty());
        } finally {
            server.stop();
        }

        assertNotNull(events.getFirst(), "the preflight must produce an exchange event");
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("OPTIONS /ok 204"),
            "and therefore an access-log line, which a filter inside CORS could not write: "
                + log.toString(StandardCharsets.UTF_8));
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
