package com.jujin.freeway.benchmarks.http;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.RequestPipeline;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class HttpEngineSmokeMain {

    private static final int DEFAULT_REQUESTS = 20_000;
    private static final int DEFAULT_CONCURRENCY = Math.max(
        1,
        Runtime.getRuntime().availableProcessors()
    );
    private static final byte[] PING_REQUEST = (
        "GET /ping HTTP/1.1\r\n" +
        "Host: 127.0.0.1\r\n" +
        "Connection: keep-alive\r\n" +
        "\r\n"
    ).getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PONG_BODY = "pong".getBytes(StandardCharsets.ISO_8859_1);

    public static void main(String[] args) throws Exception {
        new HttpEngineSmokeMain().run();
    }

    private void run() throws Exception {
        String engineId = System.getProperty("bench.engine", "freeway");
        int requests = intProperty("bench.requests", DEFAULT_REQUESTS, 1);
        int concurrency = intProperty("bench.concurrency", DEFAULT_CONCURRENCY, 1);
        int warmup = intProperty("bench.warmup", Math.max(concurrency * 100, 1), 0);
        HttpEngine engine = selectEngine(engineId);

        var pipeline = new RequestPipeline(
            new RouteIndex(
                List.of(Route.get("/ping", ctx -> ctx.send(200, "pong"))),
                List.of()
            ),
            new WebSocketIndex(List.of(), List.of()),
            CorsFilter.DEFAULT,
            HealthFilter.DEFAULT,
            List.of(),
            List.of(),
            List.of()
        );

        WebServer server = new WebServer(
            engine,
            new HttpServerConfig("127.0.0.1", 0, 128, Duration.ofSeconds(5)),
            event -> {},
            pipeline
        );

        boolean keepAlive = boolProperty("bench.keepalive", false);
        server.start();
        try {
            int port = server.port();
            // Warmup
            if (keepAlive) {
                try (PingConnection wc = new PingConnection(openSocket(port))) {
                    for (int i = 0; i < warmup; i++) {
                        if (!wc.sendPing()) throw new IOException("Warmup failed");
                    }
                }
            } else {
                for (int i = 0; i < warmup; i++) {
                    try (PingConnection c = new PingConnection(openSocket(port))) {
                        if (!c.sendPing()) throw new IOException("Warmup failed");
                    }
                }
            }

            long[] latencies = new long[requests];
            AtomicInteger next = new AtomicInteger();
            AtomicInteger success = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            ExecutorService workers = Executors.newFixedThreadPool(concurrency);
            long started = System.nanoTime();
            try {
                List<Future<?>> futures = new ArrayList<>(concurrency);
                if (keepAlive) {
                    for (int i = 0; i < concurrency; i++) {
                        futures.add(workers.submit(() -> {
                            try (PingConnection c = new PingConnection(openSocket(port))) {
                                while (true) {
                                    int idx = next.getAndIncrement();
                                    if (idx >= requests) return null;
                                    long ts = System.nanoTime();
                                    try {
                                        if (c.sendPing()) {
                                            latencies[success.getAndIncrement()] =
                                                (System.nanoTime() - ts) / 1_000L;
                                        } else {
                                            errors.incrementAndGet();
                                        }
                                    } catch (Exception ex) {
                                        errors.incrementAndGet();
                                    }
                                }
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        }));
                    }
                } else {
                    for (int i = 0; i < concurrency; i++) {
                        futures.add(workers.submit(() -> {
                            while (true) {
                                int idx = next.getAndIncrement();
                                if (idx >= requests) return null;
                                try (PingConnection c = new PingConnection(openSocket(port))) {
                                    long ts = System.nanoTime();
                                    if (c.sendPing()) {
                                        latencies[success.getAndIncrement()] =
                                            (System.nanoTime() - ts) / 1_000L;
                                    } else {
                                        errors.incrementAndGet();
                                    }
                                } catch (Exception ex) {
                                    errors.incrementAndGet();
                                }
                            }
                        }));
                    }
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (ExecutionException ex) {
                        throw unwrap(ex);
                    }
                }
            } finally {
                workers.shutdownNow();
            }
            long elapsed = System.nanoTime() - started;

            int ok = success.get();
            long[] sample = Arrays.copyOf(latencies, ok);
            Arrays.sort(sample);
            long p50 = percentile(sample, 0.50);
            long p95 = percentile(sample, 0.95);
            long p99 = percentile(sample, 0.99);
            double rps = ok * 1_000_000_000.0 / elapsed;

            System.out.printf(
                Locale.ROOT,
                "%-12s requests=%d ok=%d errors=%d rps=%.0f p50=%dus p95=%dus p99=%dus%n",
                engineId,
                requests,
                ok,
                errors.get(),
                rps,
                p50,
                p95,
                p99
            );
        } finally {
            server.close();
        }
    }

    private static HttpEngine selectEngine(String engineId) {
        JsonCodecDefault jsonCodec = new JsonCodecDefault();
        CoercerDefault coercer = new CoercerDefault();
        return switch (engineId.toLowerCase(Locale.ROOT)) {
            case "freeway" -> new FreewayHttpEngine(jsonCodec, coercer);
            case "robaho" -> createExternalEngine(
                "com.jujin.freeway.http.robaho.RobahoWebEngine",
                jsonCodec,
                coercer
            );
            case "undertow" -> createExternalEngine(
                "com.jujin.freeway.http.undertow.UndertowWebEngine",
                jsonCodec,
                coercer
            );
            default -> throw new IllegalArgumentException(
                "Unknown bench.engine: " + engineId
            );
        };
    }

    private static HttpEngine createExternalEngine(
        String className,
        JsonCodecDefault jsonCodec,
        CoercerDefault coercer
    ) {
        try {
            Class<?> type = Class.forName(className);
            var ctor = type.getDeclaredConstructor(
                com.jujin.freeway.commons.json.JsonCodec.class,
                com.jujin.freeway.commons.coercion.Coercer.class
            );
            ctor.setAccessible(true);
            return (HttpEngine) ctor.newInstance(jsonCodec, coercer);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(
                "Failed to load benchmark engine class: " + className,
                ex
            );
        }
    }

    private static boolean boolProperty(String name, boolean defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        return "true".equalsIgnoreCase(raw.trim()) || "1".equals(raw.trim());
    }

    private static int intProperty(String name, int defaultValue, int minValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value = Integer.parseInt(raw.trim());
        if (value < minValue) {
            throw new IllegalArgumentException(
                name + " must be >= " + minValue + ": " + value
            );
        }
        return value;
    }

    private static long percentile(long[] values, double fraction) {
        if (values.length == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(values.length * fraction) - 1;
        if (index < 0) {
            index = 0;
        } else if (index >= values.length) {
            index = values.length - 1;
        }
        return values[index];
    }

    private static Exception unwrap(ExecutionException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof Exception exception) {
            return exception;
        }
        return new RuntimeException(cause);
    }

    private static Socket openSocket(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());
        return socket;
    }

    private static final class PingConnection implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        private PingConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        private boolean sendPing() throws IOException {
            out.write(PING_REQUEST);
            out.flush();

            String statusLine = readLine(in);
            if (statusLine == null || !statusLine.startsWith("HTTP/1.1 200")) {
                return false;
            }

            int contentLength = -1;
            while (true) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) {
                    break;
                }
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (contentLength < 0) {
                return false;
            }
            byte[] body = readFully(in, contentLength);
            return Arrays.equals(body, PONG_BODY);
        }

        public void close() throws IOException {
            socket.close();
        }

        private static String readLine(InputStream in) throws IOException {
            StringBuilder sb = new StringBuilder(64);
            int prev = -1;
            while (true) {
                int ch = in.read();
                if (ch == -1) {
                    return sb.length() == 0 ? null : sb.toString();
                }
                if (prev == '\r' && ch == '\n') {
                    sb.setLength(sb.length() - 1);
                    return sb.toString();
                }
                sb.append((char) ch);
                prev = ch;
            }
        }

        private static byte[] readFully(InputStream in, int length) throws IOException {
            byte[] data = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = in.read(data, offset, length - offset);
                if (read < 0) {
                    throw new IOException("Unexpected end of stream");
                }
                offset += read;
            }
            return data;
        }
    }
}
