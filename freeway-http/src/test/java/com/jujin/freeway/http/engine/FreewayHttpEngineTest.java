package com.jujin.freeway.http.engine;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.http.websocket.WebSocketSession;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FreewayHttpEngineTest {
    private com.jujin.freeway.boot.AppRuntime app;

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.close();
            app = null;
        }
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(HttpConfigKeys.SERVER_HOST);
        System.clearProperty("freeway.web.server.port");
        System.clearProperty("freeway.web.server.host");
    }

    @Test
    void servesRoutes() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        app = FreewayApp.run(new String[0], new PingModule());
        assertTrue(app.get(WebServer.class).isRunning());

        HttpClient client = HttpClient.newHttpClient();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/ping"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
        assertEquals("pong", response.body());
    }

    @Test
    void servesRoutesWithLegacyWebKeys() throws Exception {
        int port = freePort();
        System.setProperty("freeway.web.server.host", "127.0.0.1");
        System.setProperty("freeway.web.server.port", String.valueOf(port));

        app = FreewayApp.run(new String[0], new PingModule());
        assertTrue(app.get(WebServer.class).isRunning());

        HttpClient client = HttpClient.newHttpClient();
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/ping"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
        assertEquals("pong", response.body());
    }

    @Test
    void websocketEchoesMessages() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        app = FreewayApp.run(new String[0], new PingModule());
        assertTrue(app.get(WebServer.class).isRunning());

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<String> received = new CompletableFuture<>();
        CompletableFuture<String> closed = new CompletableFuture<>();
        WebSocket socket = client.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/ws/lobby"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }
                @Override
                public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                    received.complete(data.toString());
                    webSocket.request(1);
                    return CompletableFuture.completedFuture(null);
                }
                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closed.complete(reason);
                    return CompletableFuture.completedFuture(null);
                }
            }).join();

        socket.sendText("hello", true).join();
        String message = received.get(5, TimeUnit.SECONDS);
        assertTrue(message.startsWith("echo:hello:lobby:"));
        assertTrue(message.length() > "echo:hello:lobby:".length());

        socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join();
        assertEquals("bye", closed.get(5, TimeUnit.SECONDS));
    }

    @Test
    void sseStreamReturnsEvents() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        CompletableFuture<Void> serverDone = new CompletableFuture<>();

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(
                Route.get("/sse", ctx -> {
                    try (var emitter = ctx.sse()) {
                        emitter.send("hello");
                        emitter.send("world");
                    }
                    serverDone.complete(null);
                })
            )
        );
        assertTrue(app.get(WebServer.class).isRunning());

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
        // The JDK HttpClient transparently decodes chunked transfer encoding,
        // so the body should be the raw SSE data.
        assertEquals("data: hello\n\ndata: world\n\n", r.body());
        assertNull(serverDone.get(5, TimeUnit.SECONDS));
    }

    @Test
    void websocketLifecycleInvokesOpenAndErrorCallbacks() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        CompletableFuture<Void> opened = new CompletableFuture<>();
        CompletableFuture<Void> errored = new CompletableFuture<>();

        app = FreewayApp.run(new String[0], binder -> {
            binder.contribute(WebSocketGroup.class).add(WebSocketGroup.of("/ws",
                WebSocketRoute.of("/lifecycle", session -> new WebSocketListener() {
                    @Override
                    public void onOpen(WebSocketSession s) throws Exception {
                        opened.complete(null);
                    }
                    @Override
                    public void onText(String text) throws Exception {
                        throw new IllegalStateException("boom");
                    }
                    @Override
                    public void onError(Throwable error) {
                        errored.complete(null);
                    }
                })
            ));
        });
        assertTrue(app.get(WebServer.class).isRunning());

        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<Integer> closed = new CompletableFuture<>();
        WebSocket socket = client.newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/lifecycle"), new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.request(1);
                }
                @Override
                public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                    closed.complete(statusCode);
                    return CompletableFuture.completedFuture(null);
                }
            }).join();

        opened.get(5, TimeUnit.SECONDS);
        socket.sendText("boom", true).join();
        assertTrue(closed.get(5, TimeUnit.SECONDS) != WebSocket.NORMAL_CLOSURE);
        errored.get(5, TimeUnit.SECONDS);
    }

    @Test
    void oversizedRequestBodyReturnsPayloadTooLarge() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(Route.post("/echo", ctx -> {
                ctx.maxBodySize(3);
                ctx.send(200, ctx.bodyText());
            }))
        );
        assertTrue(app.get(WebServer.class).isRunning());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("abcd"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(413, response.statusCode());
        assertTrue(response.body().contains("Payload Too Large"));
    }

    @Test
    void staticResourceFallthroughContinuesToRoutes(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("existing.txt"), "static file");

        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        app = FreewayApp.run(new String[0], binder -> {
            binder.contribute(StaticResourceMount.class).add(
                StaticResourceMount.directory("/", tempDir).fallthrough(true)
            );
            binder.contribute(Route.class).add(
                Route.get("/missing.txt", ctx -> ctx.send(200, "route handled"))
            );
        });
        assertTrue(app.get(WebServer.class).isRunning());

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
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    static final class PingModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(Route.class).add(Route.get("/ping", ctx -> ctx.send(200, "pong")));
            binder.contribute(WebSocketGroup.class).add(WebSocketGroup.of("/api",
                WebSocketRoute.of("/ws/{room}", session -> new WebSocketListener() {
                    @Override
                    public void onText(String text) throws Exception {
                        session.sendText("echo:" + text + ":" + session.pathVar("room") + ":" + session.requestContext().correlationId());
                        session.flush();
                    }
                })
            ));
        }
    }

    // ── HEAD response Content-Length (RFC 7231 §4.3.2) ────────────

    @Test
    void headResponseReportsCorrectContentLength() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .route(Route.get("/data", ctx ->
                ctx.send(200, "Hello World")))
            .build();
        server.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + server.port() + "/data"))
                    .method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody())
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertTrue(resp.headers().firstValue("Content-Length").isPresent());
            int cl = Integer.parseInt(resp.headers().firstValue("Content-Length").get());
            assertEquals(11, cl); // "Hello World".length
            assertEquals("", resp.body()); // no body for HEAD
        } finally {
            server.stop();
        }
    }

    // ── X-Request-Id propagation ──────────────────────────────────

    @Test
    void propagatesClientXRequestId() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .route(Route.get("/whoami", ctx ->
                ctx.send(200, ctx.requestContext().correlationId())))
            .build();
        server.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + server.port() + "/whoami"))
                    .header("X-Request-Id", "client-supplied-id")
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals("client-supplied-id", resp.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void propagatesLowercaseXRequestId() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .route(Route.get("/whoami", ctx ->
                ctx.send(200, ctx.requestContext().correlationId())))
            .build();
        server.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:" + server.port() + "/whoami"))
                    .header("x-request-id", "lowercase-client-id")
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals("lowercase-client-id", resp.body());
        } finally {
            server.stop();
        }
    }

    // ── Handler class injection ─────────────────────────────────

    static class GreetingService {
        String greet(String name) { return "Hello, " + name + "!"; }
    }

    static class GreetHandler implements RouteHandler {
        private final GreetingService service;

        @Inject
        GreetHandler(GreetingService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpContext ctx) throws Exception {
            String name = ctx.pathVar("name").orElse(null);
            ctx.send(200, service.greet(name));
        }
    }

    @Test
    void servesRouteWithInjectedHandlerClass() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        app = FreewayApp.run(new String[0], binder -> {
            binder.bind(GreetingService.class).to(GreetingService.class);
            binder.contribute(Route.class).add(
                Route.get("/greet/{name}", GreetHandler.class));
        });
        assertTrue(app.get(WebServer.class).isRunning());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/greet/Alice"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("Hello, Alice!", resp.body());
    }
}
