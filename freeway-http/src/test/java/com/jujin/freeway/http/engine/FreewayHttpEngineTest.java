package com.jujin.freeway.http.engine;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
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
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        System.clearProperty(HttpConfigKeys.MAX_BODY_SIZE);
        System.clearProperty("freeway.web.server.port");
        System.clearProperty("freeway.web.server.host");
    }

    @Test
    void maxBodySizeConfigKeyIsHonored() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.MAX_BODY_SIZE, "8");

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(
                Route.post("/upload", ctx -> {
                    ctx.body();
                    ctx.send(200, "ok");
                })
            ));
        assertTrue(app.get(WebServer.class).isRunning());

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/upload"))
                .POST(HttpRequest.BodyPublishers.ofString("0123456789"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(413, resp.statusCode(),
            "freeway.http.max-body-size must be applied to request bodies");
    }

    @Test
    void bodyOnGetWithoutContentLengthReturnsEmptyAndKeepsConnectionUsable() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/read", ctx -> ctx.send(200, "len=" + ctx.body().length)))
            .route(Route.get("/ping", ctx -> ctx.send(200, "pong")))
            .build();
        server.start();
        try (var sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(3000);
            var out = sock.getOutputStream();
            out.write((
                "GET /read HTTP/1.1\r\nHost: x\r\n\r\n"
                    + "GET /ping HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String response = new String(
                sock.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("len=0"),
                "Unframed GET body must read as empty: " + response);
            assertTrue(response.contains("pong"),
                "Keep-alive connection must stay usable: " + response);
        } finally {
            server.stop();
        }
    }

    @Test
    void unreadPostBodyDoesNotBreakKeepAlive() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.post("/ignore", ctx -> ctx.send(200, "ok")))
            .route(Route.get("/ping", ctx -> ctx.send(200, "pong")))
            .build();
        server.start();
        try (var sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(3000);
            var out = sock.getOutputStream();
            out.write(("POST /ignore HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Content-Length: 5\r\n"
                    + "\r\n"
                    + "hello")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String first = readHttpResponse(sock);
            assertTrue(first.contains("200"),
                "POST response missing: " + first);

            // The unread body must be drained exactly, leaving the keep-alive
            // connection usable for the next request.
            out.write(("GET /ping HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Connection: close\r\n"
                    + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String second = readHttpResponse(sock);
            assertTrue(second.contains("pong"),
                "Second request on the same connection failed: " + second);
        } finally {
            server.stop();
        }
    }

    @Test
    void sseClosesConnectionAfterComplete() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/sse", ctx -> {
                try (var emitter = ctx.sse()) {
                    emitter.send("hi");
                }
            }))
            .build();
        server.start();
        try (var sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(3000);
            sock.getOutputStream().write(
                "GET /sse HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            sock.getOutputStream().flush();
            String response = new String(
                sock.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.contains("Connection: close"),
                "SSE must force Connection: close: " + response);
            assertTrue(response.contains("hi"),
                "SSE body missing: " + response);
            assertTrue(response.endsWith("0\r\n\r\n"),
                "SSE stream must end with the terminal chunk: " + response);
        } finally {
            server.stop();
        }
    }

    @Test
    void h2ShutdownClosesStreamWaitingForRequestBody() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(1)))
            .route(Route.post("/upload", ctx -> {
                ctx.body();
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try (var sock = new Socket("127.0.0.1", port)) {
            var out = sock.getOutputStream();
            out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            writeH2Frame(out, 0, 4, 0, 0); // SETTINGS
            // HEADERS: POST /upload with required pseudo-headers, END_HEADERS
            // but NO END_STREAM — the handler blocks waiting for the body.
            byte[] block = {
                0x03, 0x04, 'P', 'O', 'S', 'T',                       // :method POST
                0x06, 0x04, 'h', 't', 't', 'p',                       // :scheme http
                0x04, 0x07, '/', 'u', 'p', 'l', 'o', 'a', 'd',        // :path /upload
                0x01, 0x01, 'x'                                       // :authority x
            };
            writeH2Frame(out, block.length, 1, 0x04, 1); // HEADERS, END_HEADERS
            out.write(block);
            out.flush();

            Thread.sleep(300); // let the stream handler block on body()
            server.stop();
            Thread.sleep(300); // allow the session thread to unwind

            boolean lingering = Thread.getAllStackTraces().keySet().stream()
                .anyMatch(t -> t.getName().startsWith("http-"));
            assertFalse(lingering,
                "HTTP/2 session thread must exit after shutdown");
        } finally {
            if (server.isRunning()) {
                server.stop();
            }
        }
    }

    private static void writeH2Frame(OutputStream out, int length, int type,
                                     int flags, int streamId) throws IOException {
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.write(type);
        out.write(flags);
        out.write(0);
        out.write(0);
        out.write(streamId >>> 24);
        out.write(streamId >>> 16);
        out.write(streamId >>> 8);
        out.write(streamId);
    }

    private static String readHttpResponse(Socket sock) throws IOException {
        var in = sock.getInputStream();
        var head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            head.write(b);
            if ((state == 0 || state == 2) && b == '\r') state++;
            else if ((state == 1 || state == 3) && b == '\n') state++;
            else state = 0;
        }
        String headers = head.toString(StandardCharsets.ISO_8859_1);
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT)
                    .startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] body = in.readNBytes(contentLength);
        return headers + new String(body, StandardCharsets.UTF_8);
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
    void websocketRejectsInvalidKey() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        app = FreewayApp.run(new String[0], new PingModule());
        assertTrue(app.get(WebServer.class).isRunning());

        try (var sock = new java.net.Socket("127.0.0.1", port)) {
            var out = sock.getOutputStream();
            // Send upgrade with invalid key (not base64 of 16 bytes)
            out.write("GET /api/ws/lobby HTTP/1.1\r\n".getBytes());
            out.write("Host: 127.0.0.1\r\n".getBytes());
            out.write("Upgrade: websocket\r\n".getBytes());
            out.write("Connection: Upgrade\r\n".getBytes());
            out.write("Sec-WebSocket-Key: abc\r\n".getBytes());
            out.write("Sec-WebSocket-Version: 13\r\n".getBytes());
            out.write("\r\n".getBytes());
            out.flush();

            // Read response status line
            var in = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream()));
            String line = in.readLine();
            assertNotNull(line);
            assertTrue(line.contains("400"), "Invalid key should get 400, got: " + line);
        }
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

    // ── multipart guard / WebSocket subprotocol / parser hardening ──

    @Test
    void isMultipartDoesNotReadBodyForNonMultipartRequest() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig(
                "127.0.0.1", 0, 0, 1024, Duration.ofSeconds(2), 1024))
            .route(Route.post("/check", ctx ->
                ctx.send(200, "is-multipart=" + ctx.isMultipart())))
            .build();
        server.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            String body = "x".repeat(2048);
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + server.port() + "/check"))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(),
                "isMultipart() on a non-multipart request must not read the body: " + resp.body());
            assertEquals("is-multipart=false", resp.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void webSocketSubprotocolIsNegotiated() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .webSocketRoute(WebSocketRoute.of("/ws/sub", new com.jujin.freeway.http.websocket.WebSocketEndpoint() {
                @Override
                public com.jujin.freeway.http.websocket.WebSocketListener open(
                        com.jujin.freeway.http.websocket.WebSocketSession session) {
                    return new com.jujin.freeway.http.websocket.WebSocketListener() {
                        @Override public void onText(String text) throws Exception {}
                    };
                }

                @Override
                public java.util.Set<String> subprotocols() {
                    return java.util.Set.of("chat");
                }
            }))
            .build();
        server.start();
        try {
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                String key = java.util.Base64.getEncoder().encodeToString(
                    new byte[16]); // valid 16-byte nonce
                String req = "GET /ws/sub HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Protocol: chat, superchat\r\n\r\n";
                socket.getOutputStream().write(req.getBytes(
                    java.nio.charset.StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                byte[] buf = new byte[1024];
                int n = socket.getInputStream().read(buf);
                String response = new String(buf, 0, Math.max(n, 0),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(response.startsWith("HTTP/1.1 101"), response);
                assertTrue(response.contains("Sec-WebSocket-Protocol: chat"),
                    "server must select the first client protocol the endpoint supports: " + response);
                assertFalse(response.contains("superchat"), response);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void leadingEmptyLineIsIgnoredOnKeepAlive() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    "\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(
                        java.nio.charset.StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                byte[] buf = new byte[256];
                int n = socket.getInputStream().read(buf);
                String response = new String(buf, 0, Math.max(n, 0),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
                assertTrue(response.startsWith("HTTP/1.1 200"),
                    "a leading empty line must be ignored, got: " + response);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void negativeContentLengthIsRejected() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n".getBytes(
                        java.nio.charset.StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                int read = socket.getInputStream().read();
                assertEquals(-1, read,
                    "a negative Content-Length must close the connection");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void healthzMatchesTrailingSlash() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "root")))
            .build();
        server.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + server.port() + "/healthz/"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(),
                "/healthz/ must match the health filter path: " + resp.body());
        } finally {
            server.stop();
        }
    }

    @Test
    void configRejectsNonPositiveMaxBodySize() {
        assertThrows(IllegalArgumentException.class, () ->
            new HttpServerConfig("127.0.0.1", 0, 0, 1024, Duration.ofSeconds(2), 0));
    }

    // ── transport security flag ────────────────────────────────────

    @Test
    void plainHttpRequestIsNotSecure() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/secure", ctx -> ctx.send(200, String.valueOf(ctx.isSecure()))))
            .build();
        server.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + server.port() + "/secure"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals("false", resp.body(),
                "plain HTTP requests must report isSecure() == false");
        } finally {
            server.stop();
        }
    }

    @Test
    void plainHttpRequestHasNoSslSession() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/session", ctx ->
                ctx.send(200, String.valueOf(ctx.sslSession() != null))))
            .build();
        server.start();
        try {
            var client = java.net.http.HttpClient.newHttpClient();
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + server.port() + "/session"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            assertEquals("false", resp.body(),
                "plain HTTP requests must have a null SSL session");
        } finally {
            server.stop();
        }
    }

    // ── HTTP/2 h2c prior-knowledge ─────────────────────────────────

    @Test
    void h2cPriorKnowledgeGetsServerPrefaceAndSettings() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(preface);
                socket.getOutputStream().flush();
                byte[] buf = new byte[64];
                int off = 0;
                while (off < 24) {
                    int n = socket.getInputStream().read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                assertTrue(off >= 24,
                    "server must respond with its HTTP/2 preface, got " + off + " bytes");
                byte[] got = new byte[24];
                System.arraycopy(buf, 0, got, 0, 24);
                assertArrayEquals(preface, got,
                    "server connection preface must be sent before any frame");
            }
        } finally {
            server.stop();
        }
    }

    // ── oversized request line ────────────────────────────────────

    @Test
    void rejectsOversizedRequestLine() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                String line = "GET /" + "A".repeat(9000) + " HTTP/1.1\r\n\r\n";
                socket.getOutputStream().write(line.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                int read = socket.getInputStream().read();
                assertEquals(-1, read,
                    "oversized request line must close the connection, not buffer unboundedly");
            }
        } finally {
            server.stop();
        }
    }

    // ── graceful shutdown drains in-flight requests ───────────────

    @Test
    void closeWaitsForInFlightRequestWithinGrace() throws Exception {
        var handlerStarted = new java.util.concurrent.CountDownLatch(1);
        var releaseHandler = new java.util.concurrent.CountDownLatch(1);
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/slow", ctx -> {
                handlerStarted.countDown();
                releaseHandler.await();
                ctx.send(200, "slow-done");
            }))
            .build();
        server.start();
        try {
            var socket = new java.net.Socket("127.0.0.1", server.port());
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(
                "GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(
                    java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            assertTrue(handlerStarted.await(3, TimeUnit.SECONDS));

            Thread closer = new Thread(server::stop);
            closer.start();
            Thread.sleep(150);
            assertTrue(closer.isAlive(),
                "close() must wait for the in-flight request within the grace window");

            releaseHandler.countDown();
            closer.join(3000);
            assertFalse(closer.isAlive(), "close() must return after the request completes");

            byte[] buf = new byte[256];
            int n = socket.getInputStream().read(buf);
            String response = n > 0
                ? new String(buf, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1)
                : "";
            assertTrue(response.contains("200") && response.contains("slow-done"),
                "the in-flight request must complete before the connection closes: " + response);
            socket.close();
        } finally {
            server.stop();
        }
    }

    // ── HTTPS: transport security + TLS session ────────────────────

    @Test
    void httpsRequestReportsSecureAndSslSession(@TempDir Path tempDir) throws Exception {
        Path keystore = tempDir.resolve("test.p12");
        Process keytool = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-alias", "test",
                "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-dname", "CN=localhost", "-validity", "1",
                "-ext", "SAN=dns:localhost")
            .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS) && keytool.exitValue() == 0,
            "keytool should generate a keystore");

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        javax.net.ssl.SSLContext serverSsl = javax.net.ssl.SSLContext.getInstance("TLS");
        serverSsl.init(kmf.getKeyManagers(), null, null);

        FreewayHttpEngine engine = new FreewayHttpEngine(
            new com.jujin.freeway.commons.json.JsonCodecDefault(),
            new com.jujin.freeway.commons.coercion.CoercerDefault(),
            serverSsl,
            false
        );
        var handle = engine.start(
            new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)),
            ctx -> ctx.sendJson(200, java.util.Map.of(
                "secure", ctx.isSecure(),
                "session", ctx.sslSession() != null,
                "protocol", ctx.sslSession() != null
                    ? ctx.sslSession().getProtocol() : ""))
        );
        try {
            javax.net.ssl.SSLContext trustAll = javax.net.ssl.SSLContext.getInstance("TLS");
            trustAll.init(null, new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(
                        java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public void checkServerTrusted(
                        java.security.cert.X509Certificate[] chain, String authType) {}
                    @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                }
            }, new java.security.SecureRandom());

            var client = java.net.http.HttpClient.newBuilder()
                .sslContext(trustAll).build();
            var resp = client.send(
                java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("https://localhost:" + handle.port() + "/tls"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), resp.body());
            var body = com.jujin.freeway.commons.json.JsonUtils.parseObject(resp.body());
            assertTrue(body.getBoolean("secure"),
                "HTTPS requests must report isSecure() == true: " + resp.body());
            assertTrue(body.getBoolean("session"),
                "HTTPS requests must expose the TLS session: " + resp.body());
            assertFalse(body.getString("protocol").isBlank(),
                "TLS protocol must be negotiated: " + resp.body());
        } finally {
            handle.close();
        }
    }

    // ── HEAD response Content-Length (RFC 7231 §4.3.2) ────────────

    @Test
    void headResponseReportsCorrectContentLength() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
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
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
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
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
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

    // ── H2 integration ──────────────────────────────────────────────

    @Test
    void http2HandlerExceptionReturnsErrorNot200() throws Exception {
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));

        app = FreewayApp.run(new String[0], binder ->
                binder.contribute(Route.class).add(
                        Route.get("/h2-error", ctx -> {
                            throw new RuntimeException("forced error");
                        })
                ));
        assertTrue(app.get(WebServer.class).isRunning());

        // JDK HttpClient with version(HTTP_2) attempts h2c upgrade on cleartext.
        // If server supports h2c → 101 → H2 frames → handler error → RST_STREAM.
        // If server doesn't support h2c → HTTP/1.1 fallback → handler error → 500.
        // Either way: handler exception must NOT return 200.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
        try {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/h2-error"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, resp.statusCode(),
                    "Handler exception must not return 200 on any protocol, got " + resp.statusCode());
        } catch (IOException e) {
            // Acceptable: H2 RST_STREAM → connection RST
        }
    }
}
