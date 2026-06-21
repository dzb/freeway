package com.jujin.freeway.benchmarks;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.*;
import com.jujin.freeway.http.route.*;
import com.jujin.freeway.http.websocket.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

/** Wraps benchmark servers — freeway WebServer or bare native HttpServer. */
public final class ServerHarness implements AutoCloseable {
    private static final byte[] PONG = "pong".getBytes(StandardCharsets.UTF_8);
    private final AutoCloseable server;
    private final int port;
    private ServerHarness(AutoCloseable s, int p) { server = s; port = p; }
    public int port() { return port; }
    @Override public void close() throws Exception { server.close(); }

    public static ServerHarness start(String engine, String mode) throws Exception {
        boolean ws = "ws".equalsIgnoreCase(mode);
        return switch (engine.toLowerCase()) {
            case "freeway" -> startFreeway(ws);
            case "jdk-native" -> startJdkNative(ws);
            case "robaho-native" -> startRobahoNative(ws);
            default -> throw new IllegalArgumentException("Unknown engine: " + engine);
        };
    }

    private static ServerHarness startFreeway(boolean ws) throws Exception {
        var pipeline = new RequestPipeline(
            new RouteIndex(ws ? List.of() : List.of(Route.get("/ping", ctx -> ctx.send(200, "pong"))), List.of()),
            ws ? new WebSocketIndex(List.of(), List.of(WebSocketGroup.of("/ws", WebSocketRoute.of("/echo",
                session -> new WebSocketListener() {
                    @Override public void onText(String text) throws Exception { session.sendText(text); }
                })))) : new WebSocketIndex(List.of(), List.of()),
            CorsFilter.DEFAULT, HealthFilter.DEFAULT, List.of(), List.of(), List.of());
        var srv = new WebServer(new FreewayHttpEngine(new JsonCodecDefault(), new CoercerDefault()),
            new HttpServerConfig("127.0.0.1", 0, 128, Duration.ofSeconds(5)), event -> {}, pipeline);
        srv.start();
        return new ServerHarness(srv, srv.port());
    }

    private static ServerHarness startJdkNative(boolean ws) throws Exception {
        System.setProperty("com.sun.net.httpserver.HttpServerProvider", "sun.net.httpserver.DefaultHttpServerProvider");
        try { return startBareHttpServer(ws); }
        finally { System.clearProperty("com.sun.net.httpserver.HttpServerProvider"); }
    }

    private static ServerHarness startRobahoNative(boolean ws) throws Exception {
        return startBareHttpServer(ws);
    }

    private static ServerHarness startBareHttpServer(boolean ws) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", ws ? new WsEchoHandler() : ex -> {
            if (!"GET".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, PONG.length);
            try (var os = ex.getResponseBody()) { os.write(PONG); }
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        return new ServerHarness(() -> server.stop(0), port);
    }

    /** Raw WebSocket echo handler — works with any bare HttpServer (JDK or robaho). */
    private static final class WsEchoHandler implements HttpHandler {
        private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        @Override public void handle(HttpExchange ex) throws IOException {
            Headers h = ex.getRequestHeaders();
            if (!"websocket".equalsIgnoreCase(h.getFirst("Upgrade"))) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            // Compute accept key
            String key = h.getFirst("Sec-WebSocket-Key");
            String accept;
            try { var md = MessageDigest.getInstance("SHA-1"); md.update((key + MAGIC).getBytes()); accept = Base64.getEncoder().encodeToString(md.digest()); }
            catch (Exception e) { ex.sendResponseHeaders(500, -1); ex.close(); return; }
            // Send 101
            ex.getResponseHeaders().add("Upgrade", "websocket");
            ex.getResponseHeaders().add("Connection", "Upgrade");
            ex.getResponseHeaders().add("Sec-WebSocket-Accept", accept);
            ex.sendResponseHeaders(101, -1);
            // Read frames and echo text
            InputStream in = ex.getRequestBody();
            OutputStream out = ex.getResponseBody();
            try {
                while (true) {
                    int b0 = in.read(); if (b0 < 0) break;
                    int b1 = in.read(); if (b1 < 0) break;
                    int op = b0 & 0x0F; boolean masked = (b1 & 0x80) != 0;
                    int len = b1 & 0x7F;
                    if (len == 126) len = (in.read() << 8) | in.read();
                    else if (len == 127) { for (int i = 0; i < 8; i++) in.read(); break; }
                    byte[] mask = new byte[4]; if (masked) { in.read(mask); }
                    byte[] payload = new byte[len]; int off = 0; while (off < len) { int n = in.read(payload, off, len - off); if (n < 0) break; off += n; }
                    if (masked) for (int i = 0; i < len; i++) payload[i] ^= mask[i % 4];
                    if (op == 0x8) break; // close
                    if (op == 0x9) { // ping → pong
                        out.write(0x8A); out.write(len); out.write(payload); out.flush();
                    } else if (op == 0x1) { // text → echo
                        out.write(0x81); // fin+text, unmasked
                        if (len < 126) out.write(len);
                        else if (len < 65536) { out.write(126); out.write(len >> 8); out.write(len); }
                        out.write(payload); out.flush();
                    }
                }
            } catch (IOException ignored) {}
            ex.close();
        }
    }
}
