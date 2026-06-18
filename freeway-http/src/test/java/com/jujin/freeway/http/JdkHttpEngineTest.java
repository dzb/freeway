package com.jujin.freeway.http;

import static org.junit.jupiter.api.Assertions.*;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.AfterEach;

import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.staticfile.StaticResourceMount;

class JdkHttpEngineTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("web.server.host");
        System.clearProperty("web.server.port");
        System.clearProperty("web.engine");
        System.clearProperty("freeway.strict");
    }

    @Test
    void servesRoutesViaJdkEngine() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "jdk");

        Container c = Freeway.create(new HttpModule(), binder ->
            binder
                .contribute(Route.class)
                .add(Route.get("/ping", ctx -> ctx.send(200, "pong")))
        );
        c.get(WebServer.class).start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/ping"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, r.statusCode());
        assertEquals("pong", r.body());
        c.close();
    }

    @Test
    void sseStreamReturnsEvents() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "jdk");

        CountDownLatch serverDone = new CountDownLatch(1);

        Container c = Freeway.create(new HttpModule(), binder ->
            binder.contribute(Route.class).add(
                Route.get("/sse", ctx -> {
                    try (var emitter = ctx.sse()) {
                        emitter.send("hello");
                        emitter.send("world");
                    }
                    serverDone.countDown();
                })
            )
        );
        c.get(WebServer.class).start();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> r = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/sse"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, r.statusCode());
        String ct = r.headers().firstValue("Content-Type").orElse("");
        assertEquals("text/event-stream; charset=utf-8", ct);
        assertEquals("data: hello\n\ndata: world\n\n", r.body());
        assertTrue(serverDone.await(5, TimeUnit.SECONDS));
        c.close();
    }

    @Test
    void oversizedRequestBodyReturnsPayloadTooLarge() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "jdk");

        Container c = Freeway.create(new HttpModule(), binder ->
            binder.contribute(Route.class).add(
                Route.post("/echo", ctx -> {
                    ctx.maxBodySize(3);
                    ctx.send(200, ctx.bodyText());
                })
            )
        );
        try {
            c.get(WebServer.class).start();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/echo"))
                    .POST(HttpRequest.BodyPublishers.ofString("abcd"))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(413, r.statusCode());
            assertTrue(r.body().contains("Payload Too Large"));
        } finally {
            c.close();
        }
    }

    @Test
    void staticResourceFallthroughContinuesToRoutes(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("existing.txt"), "static file");

        int port = freePort();
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "jdk");

        Container c = Freeway.create(new HttpModule(), binder -> {
            binder.contribute(StaticResourceMount.class).add(
                StaticResourceMount.directory("/", tempDir).fallthrough(true)
            );
            binder.contribute(Route.class).add(
                Route.get("/missing.txt", ctx -> ctx.send(200, "route handled"))
            );
        });
        try {
            c.get(WebServer.class).start();
            HttpClient client = HttpClient.newHttpClient();

            // Existing file should be served by static mount
            HttpResponse<String> r1 = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/existing.txt"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, r1.statusCode());
            assertEquals("static file", r1.body());

            // Missing file with fallthrough → route handles it
            HttpResponse<String> r2 = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/missing.txt"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, r2.statusCode());
            assertEquals("route handled", r2.body());
        } finally {
            c.close();
        }
    }

    @Test
    void engineFallsBackToJdkWhenDefaultUnavailable() throws Exception {
        int port = freePort();
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "robaho"); // not on classpath

        Container c = Freeway.create(new HttpModule(), binder ->
            binder.contribute(Route.class)
                .add(Route.get("/ping", ctx -> ctx.send(200, "pong")))
        );
        try {
            c.get(WebServer.class).start();
            assertTrue(c.get(WebServer.class).isRunning());

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> r = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/ping"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, r.statusCode());
            assertEquals("pong", r.body());
        } finally {
            c.close();
        }
    }

    @Test
    void strictModeRejectsMissingDefaultEngine() throws Exception {
        int port = freePort();
        System.setProperty("web.server.host", "127.0.0.1");
        System.setProperty("web.server.port", String.valueOf(port));
        System.setProperty("web.engine", "robaho"); // not on classpath
        System.setProperty("freeway.strict", "true");

        Container c = Freeway.create(new HttpModule(), binder ->
            binder.contribute(Route.class)
                .add(Route.get("/ping", ctx -> ctx.send(200, "pong")))
        );
        try {
            IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> c.get(WebServer.class).start()
            );
            assertTrue(ex.getMessage().contains("strict mode"));
        } finally {
            c.close();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
