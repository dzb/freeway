package com.jujin.freeway.http.engine;

import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.commons.json.JsonUtils;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
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
        System.clearProperty(HttpConfigKeys.SSL_ENABLED);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD);
        System.clearProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE);
        System.clearProperty(HttpConfigKeys.SSL_HTTP2);
        System.clearProperty("freeway.http.server.port");
        System.clearProperty("freeway.http.server.host");
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
        System.setProperty("freeway.http.server.host", "127.0.0.1");
        System.setProperty("freeway.http.server.port", String.valueOf(port));

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

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int n = in.read(buffer, off, buffer.length - off);
            if (n < 0) {
                throw new IOException(
                    "EOF after " + off + " of " + buffer.length + " bytes");
            }
            off += n;
        }
    }

    /** Like {@link #readFully} but returns false on EOF instead of throwing. */
    private static boolean readFullyOrEof(InputStream in, byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int n = in.read(buffer, off, buffer.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static Path generateKeyStore(Path dir) throws Exception {
        Path keystore = dir.resolve("test.p12");
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
        return keystore;
    }

    private static SSLContext serverSslContext(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext serverSsl = SSLContext.getInstance("TLS");
        serverSsl.init(kmf.getKeyManagers(), null, null);
        return serverSsl;
    }

    private static SSLContext trustAllSslContext() throws Exception {
        SSLContext trustAll = SSLContext.getInstance("TLS");
        trustAll.init(null, new TrustManager[]{
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        }, new SecureRandom());
        return trustAll;
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
    void h2cRejectsStreamsBeyondConcurrentCap() throws Exception {
        // MAX_CONCURRENT_STREAMS (100): the 101st concurrent stream must be
        // rejected with RST_STREAM(REFUSED_STREAM). Handlers hold streams open
        // so the cap is actually reached.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();

                // consume the server SETTINGS preface
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // HPACK block: indexed :method GET, :path /, :scheme http,
                // literal :authority "localhost" (name-indexed, 4-bit prefix).
                // HPACK block: indexed :method GET (0x82), :path / (0x84),
                // :scheme http (0x86), literal :authority "localhost".
                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                // 101 HEADERS frames — stream 201 is the one beyond the cap.
                for (int streamId = 1; streamId <= 201; streamId += 2) {
                    out.write(new byte[] {
                        (byte) ((headerBlock.length >> 16) & 0xff),
                        (byte) ((headerBlock.length >> 8) & 0xff),
                        (byte) (headerBlock.length & 0xff),
                        0x1,  // HEADERS
                        0x5,  // END_HEADERS | END_STREAM
                        (byte) ((streamId >> 24) & 0x7f),
                        (byte) ((streamId >> 16) & 0xff),
                        (byte) ((streamId >> 8) & 0xff),
                        (byte) (streamId & 0xff)
                    });
                    out.write(headerBlock);
                }
                out.flush();

                // Read frames until the rejection arrives: RST_STREAM (type 3)
                // on stream 201 with REFUSED_STREAM (0x7).
                var in = socket.getInputStream();
                boolean rejected = false;
                long deadline = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < deadline && !rejected) {
                    byte[] frameHeader = new byte[9];
                    if (!readFullyOrEof(in, frameHeader)) break;
                    int len = ((frameHeader[0] & 0xff) << 16)
                        | ((frameHeader[1] & 0xff) << 8)
                        | (frameHeader[2] & 0xff);
                    int type = frameHeader[3] & 0xff;
                    int streamId = ((frameHeader[5] & 0x7f) << 24)
                        | ((frameHeader[6] & 0xff) << 16)
                        | ((frameHeader[7] & 0xff) << 8)
                        | (frameHeader[8] & 0xff);
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (type == 0x3 && streamId == 201 && len == 4) {
                        int errorCode = ((payload[0] & 0xff) << 24)
                            | ((payload[1] & 0xff) << 16)
                            | ((payload[2] & 0xff) << 8)
                            | (payload[3] & 0xff);
                        rejected = errorCode == 0x7; // REFUSED_STREAM
                    }
                }
                assertTrue(rejected,
                    "stream 201 must be rejected with RST_STREAM(REFUSED_STREAM)");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cAcceptsTrailersWithoutKillingConnection() throws Exception {
        // A trailer HEADERS block after the request body is legitimate
        // (RFC 7540 §8.1.2.2). It must be consumed (HPACK state stays in
        // sync), discarded, and must NOT tear down the connection: a second
        // request on the same connection must still succeed.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> {
                ctx.body();
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII);
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();

                // consume the server SETTINGS preface
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // Request header block: indexed :method GET, :path /, :scheme
                // http, literal :authority "localhost".
                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                // Trailer block: literal without indexing, new name "x: v"
                // (0x00 = name index 0 → new name, then 8-bit length prefixes).
                byte[] trailerBlock = new byte[] {
                    0x00, 0x01, 'x', 0x01, 'v'
                };

                // Stream 1: HEADERS (no END_STREAM) + DATA "hi" + trailer
                // HEADERS (END_HEADERS | END_STREAM).
                writeFrame(out, headerBlock.length, 0x1, 0x4, 1, headerBlock);
                writeFrame(out, 2, 0x0, 0x0, 1, "hi".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                writeFrame(out, trailerBlock.length, 0x1, 0x5, 1, trailerBlock);

                // First response must be 200 on stream 1 and the connection
                // must survive (no GOAWAY): a second request on stream 3
                // must also get 200.
                assertTrue(waitForStatus200(socket.getInputStream(), 1, 5000),
                    "stream 1 must complete with 200 despite trailers");
                writeFrame(out, headerBlock.length, 0x1, 0x5, 3, headerBlock);
                assertTrue(waitForStatus200(socket.getInputStream(), 3, 5000),
                    "connection must survive trailers — stream 3 must get 200");
            }
        } finally {
            server.stop();
        }
    }

    private static void writeFrame(OutputStream out, int len, int type, int flags,
            int streamId, byte[] payload) throws IOException {
        out.write(new byte[] {
            (byte) ((len >> 16) & 0xff),
            (byte) ((len >> 8) & 0xff),
            (byte) (len & 0xff),
            (byte) type,
            (byte) flags,
            (byte) ((streamId >> 24) & 0x7f),
            (byte) ((streamId >> 16) & 0xff),
            (byte) ((streamId >> 8) & 0xff),
            (byte) (streamId & 0xff)
        });
        out.write(payload);
        out.flush();
    }

    /** Reads frames until a HEADERS response on {@code streamId} decodes to :status 200. */
    private static boolean waitForStatus200(InputStream in, int streamId, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            byte[] frameHeader = new byte[9];
            if (!readFullyOrEof(in, frameHeader)) return false;
            int len = ((frameHeader[0] & 0xff) << 16)
                | ((frameHeader[1] & 0xff) << 8)
                | (frameHeader[2] & 0xff);
            int type = frameHeader[3] & 0xff;
            int frameStreamId = ((frameHeader[5] & 0x7f) << 24)
                | ((frameHeader[6] & 0xff) << 16)
                | ((frameHeader[7] & 0xff) << 8)
                | (frameHeader[8] & 0xff);
            byte[] payload = new byte[len];
            readFully(in, payload);
            if (type == 0x7) return false; // GOAWAY — connection killed
            if (type == 0x1 && frameStreamId == streamId && len >= 1
                    && payload[0] == (byte) 0x88) { // indexed :status 200
                return true;
            }
        }
        return false;
    }

    @Test
    void h2cRespectsPeerMaxFrameSizeForOutboundSplitting() throws Exception {
        // The peer advertises SETTINGS_MAX_FRAME_SIZE=32768 (> our default
        // 16384): outbound DATA frames must be split to at most the peer's
        // size, and larger than the default when the peer allows it
        // (RFC 7540 §6.5.2 requires the value to be ≥ 16384).
        String big = "x".repeat(50000);
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, big)))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();

                // Consume the server SETTINGS preface.
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // SETTINGS with MAX_FRAME_SIZE=32768 (identifier 0x5).
                byte[] settingsPayload = new byte[] {
                    0x00, 0x05, 0x00, 0x00, (byte) 0x80, 0x00
                };
                writeFrame(out, settingsPayload.length, 0x4, 0x0, 0, settingsPayload);

                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                writeFrame(out, headerBlock.length, 0x1, 0x5, 1, headerBlock);

                // Collect DATA frames until END_STREAM; every frame ≤ 32768,
                // and at least one frame must exceed our default 16384.
                var in = socket.getInputStream();
                int total = 0;
                int maxData = 0;
                boolean done = false;
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline && !done) {
                    byte[] frameHeader = new byte[9];
                    if (!readFullyOrEof(in, frameHeader)) break;
                    int len = ((frameHeader[0] & 0xff) << 16)
                        | ((frameHeader[1] & 0xff) << 8)
                        | (frameHeader[2] & 0xff);
                    int type = frameHeader[3] & 0xff;
                    int flags = frameHeader[4] & 0xff;
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (type == 0x0) { // DATA
                        maxData = Math.max(maxData, len);
                        total += len;
                        done = (flags & 0x1) != 0; // END_STREAM
                    }
                }
                assertEquals(50000, total, "full response body must arrive");
                assertTrue(maxData > 16384 && maxData <= 32768,
                    "outbound DATA must follow the peer's advertised max frame size 32768, got " + maxData);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cRejectsHeaderBlockOverMaxInbound() throws Exception {
        // A header block larger than the 64 KiB inbound cap must fail the
        // connection with COMPRESSION_ERROR (GOAWAY code 9) instead of being
        // buffered without bound.
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                // 5 frames × 16384 bytes = 81920 > 65536 cap. Payload content
                // is irrelevant — the size check fires during collection.
                byte[] chunk = new byte[16384];
                writeFrame(out, chunk.length, 0x1, 0x0, 1, chunk); // HEADERS, no END_HEADERS
                for (int i = 0; i < 3; i++) {
                    writeFrame(out, chunk.length, 0x9, 0x0, 1, chunk); // CONTINUATION
                }
                writeFrame(out, chunk.length, 0x9, 0x4, 1, chunk); // END_HEADERS
                out.flush();

                var in = socket.getInputStream();
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    byte[] frameHeader = new byte[9];
                    if (!readFullyOrEof(in, frameHeader)) break;
                    int len = ((frameHeader[0] & 0xff) << 16)
                        | ((frameHeader[1] & 0xff) << 8)
                        | (frameHeader[2] & 0xff);
                    int type = frameHeader[3] & 0xff;
                    byte[] payload = new byte[len];
                    readFully(in, payload);
                    if (type == 0x7) { // GOAWAY
                        int errorCode = len >= 8 ? ((payload[4] & 0xff) << 24)
                            | ((payload[5] & 0xff) << 16)
                            | ((payload[6] & 0xff) << 8) | (payload[7] & 0xff) : -1;
                        assertEquals(0x9, errorCode,
                            "oversized header block must fail with COMPRESSION_ERROR");
                        return;
                    }
                }
                fail("expected GOAWAY(COMPRESSION_ERROR) for oversized header block");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cResetStreamWithNoErrorReleasesHandler() throws Exception {
        // RST_STREAM with NO_ERROR must still terminate the stream: a handler
        // blocked reading the request body is released, and the connection
        // survives for the next request.
        var active = new java.util.concurrent.atomic.AtomicInteger();
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> {
                active.incrementAndGet();
                try {
                    ctx.body();
                } catch (IOException ignored) {
                    // RST closes the body stream — expected.
                } finally {
                    active.decrementAndGet();
                }
                ctx.send(200, "ok");
            }))
            .build();
        server.start();
        try {
            byte[] preface = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(5000);
                var out = socket.getOutputStream();
                out.write(preface);
                out.flush();
                byte[] settingsHeader = new byte[9];
                readFully(socket.getInputStream(), settingsHeader);
                int settingsLen = ((settingsHeader[0] & 0xff) << 16)
                    | ((settingsHeader[1] & 0xff) << 8)
                    | (settingsHeader[2] & 0xff);
                readFully(socket.getInputStream(), new byte[settingsLen]);

                byte[] headerBlock = new byte[] {
                    (byte) 0x82, (byte) 0x84, (byte) 0x86, (byte) 0x41, 0x09,
                    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
                };
                // Request without END_STREAM: handler parks reading the body.
                writeFrame(out, headerBlock.length, 0x1, 0x4, 1, headerBlock);
                // Let the handler start, then RST with NO_ERROR (code 0).
                Thread.sleep(200);
                writeFrame(out, 4, 0x3, 0x0, 1, new byte[]{0, 0, 0, 0});
                out.flush();

                long deadline = System.currentTimeMillis() + 3000;
                while (active.get() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(0, active.get(),
                    "RST_STREAM(NO_ERROR) must release the blocked handler");
                writeFrame(out, headerBlock.length, 0x1, 0x5, 3, headerBlock);
                out.flush();
                assertTrue(waitForStatus200(socket.getInputStream(), 3, 5000),
                    "connection must survive RST_STREAM(NO_ERROR)");
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void h2cPriorKnowledgeGetsServerSettingsFirst() throws Exception {
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

                // RFC 7540 §3.5: the server connection preface is a SETTINGS
                // frame — the PRI magic belongs to the client and must never
                // be echoed back.
                byte[] header = new byte[9];
                readFully(socket.getInputStream(), header);
                int length = ((header[0] & 0xff) << 16)
                    | ((header[1] & 0xff) << 8)
                    | (header[2] & 0xff);
                int type = header[3] & 0xff;
                int flags = header[4] & 0xff;
                int streamId = ((header[5] & 0x7f) << 24)
                    | ((header[6] & 0xff) << 16)
                    | ((header[7] & 0xff) << 8)
                    | (header[8] & 0xff);

                assertEquals(0x4, type,
                    "server connection preface must be a SETTINGS frame");
                assertEquals(0, flags, "first SETTINGS frame must not be an ACK");
                assertEquals(0, streamId,
                    "SETTINGS must be a connection-level frame");
                assertTrue(length > 0 && length <= 6 * 6,
                    "SETTINGS payload length must fit at least one parameter, got " + length);

                byte[] payload = new byte[length];
                readFully(socket.getInputStream(), payload);
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
        Path keystore = generateKeyStore(tempDir);
        SSLContext serverSsl = serverSslContext(keystore);

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
            SSLContext trustAll = trustAllSslContext();

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

    @Test
    void httpsModuleLoadsKeyStoreFromConfig(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeyStore(tempDir);
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, keystore.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_HTTP2, "true");

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(
                Route.get("/tls-module", ctx -> ctx.sendJson(200, Map.of(
                    "secure", ctx.isSecure(),
                    "session", ctx.sslSession() != null,
                    "protocol", ctx.sslSession() != null
                        ? ctx.sslSession().getProtocol() : "")))
            ));
        assertTrue(app.get(WebServer.class).isRunning(),
            "HttpModule must start an HTTPS server when freeway.http.ssl.* is configured");

        var client = HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .version(HttpClient.Version.HTTP_2)
            .build();
        var resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + port + "/tls-module"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());

        var body = JsonUtils.parseObject(resp.body());
        assertTrue(body.getBoolean("secure"),
            "HTTPS via HttpModule must report isSecure() == true: " + resp.body());
        assertTrue(body.getBoolean("session"),
            "HTTPS via HttpModule must expose the TLS session: " + resp.body());
        assertFalse(body.getString("protocol").isBlank(),
            "TLS protocol must be negotiated: " + resp.body());
        assertEquals(HttpClient.Version.HTTP_2, resp.version(),
            "HttpModule HTTPS must negotiate HTTP/2 over TLS by default");
    }

    @Test
    void httpsNegotiatesHttp2OverTls(@TempDir Path tempDir) throws Exception {
        Path keystore = generateKeyStore(tempDir);
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .sslContext(serverSslContext(keystore), true)
            .route(Route.get("/h2-tls", ctx -> ctx.sendJson(200, Map.of(
                "secure", ctx.isSecure(),
                "protocol", ctx.sslSession() != null
                    ? ctx.sslSession().getProtocol() : ""))))
            .build();
        server.start();
        try {
            var client = HttpClient.newBuilder()
                .sslContext(trustAllSslContext())
                .version(HttpClient.Version.HTTP_2)
                .build();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("https://localhost:" + server.port() + "/h2-tls"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), resp.body());
            assertEquals(HttpClient.Version.HTTP_2, resp.version(),
                "ALPN must negotiate HTTP/2 over TLS");

            var body = JsonUtils.parseObject(resp.body());
            assertTrue(body.getBoolean("secure"),
                "h2-over-TLS requests must report isSecure() == true: " + resp.body());
            assertFalse(body.getString("protocol").isBlank(),
                "TLS protocol must be negotiated: " + resp.body());
        } finally {
            server.stop();
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
    void generatedCorrelationIdIs32CharLowercaseHex() throws Exception {
        // The fast id (ThreadLocalRandom + HexFormat) must keep the same
        // wire contract as the old UUID.randomUUID().toString().replace("-",""):
        // 32 lowercase hex chars, no hyphens.
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
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode());
            String id = resp.body();
            assertEquals(32, id.length(),
                "auto-generated correlation id must be 32 chars, got: " + id);
            assertTrue(id.matches("[0-9a-f]{32}"),
                "auto-generated correlation id must be lowercase hex, got: " + id);
        } finally {
            server.stop();
        }
    }

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
