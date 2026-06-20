package com.jujin.freeway.benchmarks.http;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.*;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.*;
import com.jujin.freeway.http.route.*;
import com.jujin.freeway.http.websocket.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * In-JVM WebSocket latency benchmark.  Run with:
 * {@code mvn -pl freeway-benchmarks exec:java -Dexec.mainClass=...WsBench}
 */
public class WsBench {
    private static final int WARMUP = 50;
    private static final int SAMPLES = 500;

    public static void main(String[] args) throws Exception {
        int port = freePort();
        var pipeline = new RequestPipeline(
            new RouteIndex(List.of(), List.of()),
            new WebSocketIndex(List.of(), List.of(
                WebSocketGroup.of("/ws", WebSocketRoute.of("/echo",
                    session -> new WebSocketListener() {
                        @Override public void onText(String text) throws Exception {
                            session.sendText(text);
                        }
                    })
                )
            )),
            CorsFilter.DEFAULT, HealthFilter.DEFAULT,
            List.of(), List.of(), List.of()
        );
        var server = new WebServer(
            new FreewayHttpEngine(new JsonCodecDefault(), new CoercerDefault()),
            new HttpServerConfig("127.0.0.1", port, 128, Duration.ofSeconds(5)),
            event -> {}, pipeline);
        server.start();

        try {
            var client = HttpClient.newHttpClient();
            long[] lats = new long[SAMPLES];
            var ready = new CompletableFuture<Void>();
            var echoRef = new AtomicReference<CompletableFuture<Void>>(
                new CompletableFuture<>());
            long[] sentNs = new long[1];

            var ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/echo"),
                    new WebSocket.Listener() {
                        @Override public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE); ready.complete(null);
                        }
                        @Override public CompletionStage<?> onText(
                                WebSocket webSocket, CharSequence data, boolean last) {
                            if (sentNs[0] > 0) {
                                int k = (int) sentNs[1];
                                if (k < lats.length) lats[k] = (System.nanoTime() - sentNs[0]) / 1000L;
                                sentNs[0] = 0;
                            }
                            echoRef.get().complete(null);
                            return CompletableFuture.completedFuture(null);
                        }
                    }).join();
            ready.get(5, TimeUnit.SECONDS);

            // Warmup
            for (int i = 0; i < WARMUP; i++) {
                echoRef.set(new CompletableFuture<>());
                ws.sendText("w", true);
                try {
                    echoRef.get().get(3, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    System.err.println("Timeout on warmup msg " + i + " — onText may not be firing");
                    throw e;
                }
            }

            // Measurement — sequential send-wait pattern
            for (int i = 0; i < SAMPLES; i++) {
                echoRef.set(new CompletableFuture<>());
                sentNs[0] = System.nanoTime();
                sentNs[1] = i; // stash index
                ws.sendText("ping", true);
                echoRef.get().get(3, TimeUnit.SECONDS);
            }

            Arrays.sort(lats);
            System.out.printf("WS echo p50=%dus p95=%dus p99=%dus%n",
                lats[SAMPLES/2], lats[SAMPLES*95/100], lats[SAMPLES*99/100]);
        } finally {
            server.close();
        }
    }

    private static int freePort() throws Exception {
        try (var s = new java.net.ServerSocket(0)) { return s.getLocalPort(); }
    }
}
