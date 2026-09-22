package com.jujin.freeway.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.Route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standalone shape: a {@link WebServer} built with no container, no module
 * and no config file — nothing from {@code freeway-ioc} or {@code freeway-boot}
 * appears in this file. What it must not be is a second set of assembly rules:
 * it goes through {@link WebServer#create}, the one derivation {@link HttpModule}
 * also calls, so the framework's error mapping and the precedence of an
 * application's own mapper hold on both paths.
 */
class WebServerStandaloneTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static FreewayHttpEngine engine() {
        return new FreewayHttpEngine(FreewayHttpEngine.Wiring.defaults(
            new JsonCodecDefault(), new CoercerDefault()));
    }

    private static HttpServerConfig loopback() {
        return HttpServerConfig.defaults().withHost("127.0.0.1").withPort(0)
            .withShutdownGrace(Duration.ofSeconds(1));
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
            .uri(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(int port, String path, String body)
            throws Exception {
        return CLIENT.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void assemblesAndServesWithoutAContainer() throws Exception {
        var server = WebServer.create(engine(), loopback(),
            RequestComponents.of(
                Route.get("/ping", ctx -> ctx.send(200, "pong")),
                Route.post("/boom", ctx -> {
                    ctx.setMaxBodySize(4);
                    ctx.body();
                    ctx.send(200, "unexpected");
                })));
        assertFalse(server.secure(), "an engine with no SSLContext serves plain HTTP");
        server.start();
        try {
            assertTrue(server.isRunning());
            assertEquals("pong", get(server.port(), "/ping").body());
            assertEquals(404, get(server.port(), "/absent").statusCode());
            assertEquals(413, post(server.port(), "/boom", "far too large").statusCode(),
                "the framework's own mapping answers on the standalone path as well");
        } finally {
            server.stop();
        }
    }

    @Test
    void applicationMapperOutranksTheBuiltInOneOnBothPaths() throws Exception {
        var server = WebServer.create(engine(), loopback(),
            RequestComponents.of(Route.post("/boom", ctx -> {
                    ctx.setMaxBodySize(4);
                    ctx.body();
                    ctx.send(200, "unexpected");
                }))
                .withErrorMapper((ctx, ex) -> {
                    ctx.send(418, "application mapper");
                    return true;
                }));
        server.start();
        try {
            assertEquals("application mapper", post(server.port(), "/boom", "too large").body(),
                "an added mapper is consulted before the framework's wherever the server came"
                    + " from — the rule sits in the one derivation, not in how parts were read");
        } finally {
            server.stop();
        }
    }

    @Test
    void partsAddedThroughTheWithersAllReachTheServer() throws Exception {
        var seen = new AtomicInteger();
        HttpFilter counting = (ctx, next) -> {
            seen.incrementAndGet();
            next.handle(ctx);
        };
        var server = WebServer.create(engine(), loopback(),
            RequestComponents.of(Route.get("/ping", ctx -> ctx.send(200, "pong")))
                .withCors(CorsFilter.defaults().withEnabled(false))
                .withHealth(HealthFilter.defaults().withPath("/alive"))
                .withFilter(counting));
        server.start();
        try {
            assertEquals(200, get(server.port(), "/alive").statusCode(),
                "the health policy moved to /alive must answer there");
            assertEquals(404, get(server.port(), "/healthz").statusCode(),
                "and no longer at the default path");
            assertEquals("pong", get(server.port(), "/ping").body());
            assertEquals(2, seen.get(),
                "the added filter ran for /ping and for the /healthz 404, and not for /alive: "
                    + "the health filter sits outside it (order -50) and answers there without "
                    + "continuing the chain");
        } finally {
            server.stop();
        }
    }
}
