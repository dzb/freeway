package com.jujin.freeway.http.engine;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.WebServerBuilder;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketEndpoint;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.http.websocket.WebSocketSession;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.annotation.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


class FreewayHttpEngineTest {

    private AppRuntime app;

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
        System.clearProperty(HttpConfigKeys.SSL_TRUST_STORE);
        System.clearProperty(HttpConfigKeys.SSL_TRUST_STORE_PASSWORD);
        System.clearProperty(HttpConfigKeys.SSL_TRUST_STORE_TYPE);
        System.clearProperty(HttpConfigKeys.SSL_CLIENT_AUTH);
        System.clearProperty(HttpConfigKeys.SSL_PROTOCOLS);
        System.clearProperty(HttpConfigKeys.SSL_CIPHERS);
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
    void chunkedBodyWithPipelinedNextRequestKeepsBoth() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.post("/echo", ctx -> ctx.send(200, new String(
                ctx.body(), StandardCharsets.UTF_8))))
            .route(Route.get("/ping", ctx -> ctx.send(200, "pong")))
            .build();
        server.start();
        try (var sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(3000);
            var out = sock.getOutputStream();
            out.write(("POST /echo HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Transfer-Encoding: chunked\r\n"
                    + "\r\n"
                    + "1\r\na\r\n"
                    + "0\r\n\r\n"
                    + "GET /ping HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Connection: close\r\n"
                    + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String first = readHttpResponse(sock);
            assertTrue(first.contains("a"),
                "chunked body response missing: " + first);
            String second = readHttpResponse(sock);
            assertTrue(second.contains("pong"),
                "pipelined request after chunked body was lost: " + second);
        } finally {
            server.stop();
        }
    }

    @Test
    void handlerConnectionCloseActuallyClosesConnection() throws Exception {
        int port = freePort();
        var server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", port, 0, Duration.ofSeconds(2)))
            .route(Route.get("/close", ctx -> {
                ctx.setHeader("Connection", "close");
                ctx.send(200, "first");
            }))
            .build();
        server.start();
        try (var sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(3000);
            var out = sock.getOutputStream();
            out.write(("GET /close HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "\r\n"
                    + "GET /close HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String first = readHttpResponse(sock);
            assertTrue(first.contains("Connection: close"),
                "handler Connection: close must appear in the response: " + first);
            assertEquals(-1, sock.getInputStream().read(),
                "server must close the connection after Connection: close");
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
            if (line.toLowerCase(Locale.ROOT)
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

        try (var sock = new Socket("127.0.0.1", port)) {
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
            var in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
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

    private static Path generateClientKeyStore(Path dir) throws Exception {
        Path keystore = dir.resolve("client.p12");
        Process keytool = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-alias", "client",
                "-keyalg", "RSA", "-keysize", "2048",
                "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-dname", "CN=freeway-client", "-validity", "1")
            .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertTrue(keytool.waitFor(30, TimeUnit.SECONDS) && keytool.exitValue() == 0,
            "keytool should generate a client keystore");
        return keystore;
    }

    private static Path generateTrustStore(Path dir, Path clientKeystore) throws Exception {
        Path cert = dir.resolve("client.cer");
        Process export = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/keytool",
                "-exportcert", "-alias", "client",
                "-keystore", clientKeystore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-file", cert.toString())
            .redirectErrorStream(true).start();
        export.getInputStream().readAllBytes();
        assertTrue(export.waitFor(30, TimeUnit.SECONDS) && export.exitValue() == 0,
            "keytool should export the client certificate");

        Path trustStore = dir.resolve("client-trust.p12");
        Process importCert = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/keytool",
                "-importcert", "-alias", "client",
                "-file", cert.toString(),
                "-keystore", trustStore.toString(),
                "-storetype", "PKCS12", "-storepass", "changeit",
                "-noprompt")
            .redirectErrorStream(true).start();
        importCert.getInputStream().readAllBytes();
        assertTrue(importCert.waitFor(30, TimeUnit.SECONDS) && importCert.exitValue() == 0,
            "keytool should import the client certificate into the truststore");
        return trustStore;
    }

    private static SSLContext clientSslContext(Path clientKeystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(clientKeystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext clientSsl = SSLContext.getInstance("TLS");
        clientSsl.init(kmf.getKeyManagers(), new TrustManager[]{
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
        return clientSsl;
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
                        session.sendText("echo:" + text + ":" + session.pathVar("room") + ":" + session.correlationId());
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
            var client = HttpClient.newHttpClient();
            String body = "x".repeat(2048);
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/check"))
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
            .webSocketRoute(WebSocketRoute.of("/ws/sub", new WebSocketEndpoint() {
                @Override
                public WebSocketListener open(
                        WebSocketSession session) {
                    return new WebSocketListener() {
                        @Override public void onText(String text) throws Exception {}
                    };
                }

                @Override
                public Set<String> subprotocols() {
                    return Set.of("chat");
                }
            }))
            .build();
        server.start();
        try {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                String key = Base64.getEncoder().encodeToString(
                    new byte[16]); // valid 16-byte nonce
                String req = "GET /ws/sub HTTP/1.1\r\n"
                    + "Host: x\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Protocol: chat, superchat\r\n\r\n";
                socket.getOutputStream().write(req.getBytes(
                    StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                byte[] buf = new byte[1024];
                int n = socket.getInputStream().read(buf);
                String response = new String(buf, 0, Math.max(n, 0),
                    StandardCharsets.ISO_8859_1);
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
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    "\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(
                        StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                byte[] buf = new byte[256];
                int n = socket.getInputStream().read(buf);
                String response = new String(buf, 0, Math.max(n, 0),
                    StandardCharsets.ISO_8859_1);
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
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(
                    "GET / HTTP/1.1\r\nHost: x\r\nContent-Length: -1\r\n\r\n".getBytes(
                        StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                // Malformed framing is answered 400 instead of silently
                // dropping the connection.
                var collected = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                while (true) {
                    int n = socket.getInputStream().read(buf);
                    if (n < 0) break;
                    collected.write(buf, 0, n);
                }
                assertTrue(collected.toString(StandardCharsets.ISO_8859_1)
                        .contains("400"),
                    "a negative Content-Length must be answered 400, got: "
                        + collected.toString(StandardCharsets.ISO_8859_1));
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
            var client = HttpClient.newHttpClient();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/healthz/"))
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
            var client = HttpClient.newHttpClient();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/secure"))
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
            var client = HttpClient.newHttpClient();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.port() + "/session"))
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

    /** Reads frames until a HEADERS response on {@code streamId} decodes to :status 200. */

    // ── oversized request line ────────────────────────────────────

    @Test
    void rejectsOversizedRequestLine() throws Exception {
        WebServer server = WebServerBuilder.builder()
            .config(new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)))
            .route(Route.get("/", ctx -> ctx.send(200, "ok")))
            .build();
        server.start();
        try {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(3000);
                String line = "GET /" + "A".repeat(9000) + " HTTP/1.1\r\n\r\n";
                socket.getOutputStream().write(line.getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                // The server must reject the request with a clear 400 instead
                // of just dropping the connection.
                var collected = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                while (true) {
                    int n = socket.getInputStream().read(buf);
                    if (n < 0) break;
                    collected.write(buf, 0, n);
                }
                assertTrue(collected.toString(StandardCharsets.ISO_8859_1)
                        .contains("400"),
                    "oversized request line must be answered 400, got: "
                        + collected.toString(StandardCharsets.ISO_8859_1));
            }
        } finally {
            server.stop();
        }
    }

    // ── graceful shutdown drains in-flight requests ───────────────

    @Test
    void closeWaitsForInFlightRequestWithinGrace() throws Exception {
        var handlerStarted = new CountDownLatch(1);
        var releaseHandler = new CountDownLatch(1);
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
            var socket = new Socket("127.0.0.1", server.port());
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(
                "GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(
                    StandardCharsets.ISO_8859_1));
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
                ? new String(buf, 0, n, StandardCharsets.ISO_8859_1)
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
            new JsonCodecDefault(),
            new CoercerDefault(),
            serverSsl,
            false
        );
        var handle = engine.start(
            new HttpServerConfig("127.0.0.1", 0, 0, Duration.ofSeconds(2)),
            ctx -> ctx.sendJson(200, Map.of(
                "secure", ctx.isSecure(),
                "session", ctx.sslSession() != null,
                "protocol", ctx.sslSession() != null
                    ? ctx.sslSession().getProtocol() : ""))
        );
        try {
            SSLContext trustAll = trustAllSslContext();

            var client = HttpClient.newBuilder()
                .sslContext(trustAll).build();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("https://localhost:" + handle.port() + "/tls"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), resp.body());
            var body = JsonUtils.parseObject(resp.body());
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

    @Test
    void httpsModuleRestrictsProtocolsAndCiphersFromConfig(@TempDir Path tempDir)
            throws Exception {
        Path keystore = generateKeyStore(tempDir);
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, keystore.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_HTTP2, "false");
        System.setProperty(HttpConfigKeys.SSL_PROTOCOLS, "TLSv1.3");
        System.setProperty(HttpConfigKeys.SSL_CIPHERS, "TLS_AES_128_GCM_SHA256");

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(
                Route.get("/tls-config", ctx -> ctx.sendJson(200, Map.of(
                    "protocol", ctx.sslSession() != null
                        ? ctx.sslSession().getProtocol() : "")))
            ));
        assertTrue(app.get(WebServer.class).isRunning());

        var client = HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        var resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + port + "/tls-config"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        var body = JsonUtils.parseObject(resp.body());
        assertEquals("TLSv1.3", body.getString("protocol"),
            "ssl.protocols must restrict the negotiated TLS version");

        var legacyClient = HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .version(HttpClient.Version.HTTP_1_1)
            .sslParameters(legacyTlsOnly())
            .build();
        assertThrows(IOException.class, () -> legacyClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + port + "/tls-config"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString()),
            "a TLSv1.2-only client must be rejected when ssl.protocols=TLSv1.3");
    }

    @Test
    void httpsModuleClientAuthRequiresTrustedClientCertificate(@TempDir Path tempDir)
            throws Exception {
        Path keystore = generateKeyStore(tempDir);
        Path clientKeystore = generateClientKeyStore(tempDir);
        Path trustStore = generateTrustStore(tempDir, clientKeystore);
        int port = freePort();
        System.setProperty(HttpConfigKeys.SERVER_HOST, "127.0.0.1");
        System.setProperty(HttpConfigKeys.SERVER_PORT, String.valueOf(port));
        System.setProperty(HttpConfigKeys.SSL_ENABLED, "true");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE, keystore.toString());
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_HTTP2, "false");
        System.setProperty(HttpConfigKeys.SSL_TRUST_STORE, trustStore.toString());
        System.setProperty(HttpConfigKeys.SSL_TRUST_STORE_PASSWORD, "changeit");
        System.setProperty(HttpConfigKeys.SSL_TRUST_STORE_TYPE, "PKCS12");
        System.setProperty(HttpConfigKeys.SSL_CLIENT_AUTH, "true");

        app = FreewayApp.run(new String[0], binder ->
            binder.contribute(Route.class).add(
                Route.get("/mtls", ctx -> ctx.sendJson(200, Map.of(
                    "secure", ctx.isSecure(),
                    "session", ctx.sslSession() != null)))
            ));
        assertTrue(app.get(WebServer.class).isRunning());

        // A client without a certificate must fail the TLS handshake.
        var anonymous = HttpClient.newBuilder()
            .sslContext(trustAllSslContext())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        assertThrows(IOException.class, () -> anonymous.send(
            HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + port + "/mtls"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString()),
            "ssl.client-auth=true must reject clients without a certificate");

        // A client presenting the trusted certificate succeeds.
        var authenticated = HttpClient.newBuilder()
            .sslContext(clientSslContext(clientKeystore))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        var resp = authenticated.send(
            HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + port + "/mtls"))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        var body = JsonUtils.parseObject(resp.body());
        assertTrue(body.getBoolean("secure"));
        assertTrue(body.getBoolean("session"));
    }

    private static javax.net.ssl.SSLParameters legacyTlsOnly() {
        var params = new javax.net.ssl.SSLParameters();
        params.setProtocols(new String[]{"TLSv1.2"});
        return params;
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
            // Pin HTTP/1.1: this regression is about RFC 7231 §4.3.2 HEAD
            // Content-Length semantics, which HTTP/2 expresses with DATA
            // framing rather than a header.
            var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + server.port() + "/data"))
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
                ctx.send(200, ctx.correlationId())))
            .build();
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + server.port() + "/whoami"))
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
                ctx.send(200, ctx.correlationId())))
            .build();
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + server.port() + "/whoami"))
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
                ctx.send(200, ctx.correlationId())))
            .build();
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            var resp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + server.port() + "/whoami"))
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
