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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import robaho.net.httpserver.DefaultHttpServerProvider;
import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class HttpEngineDecisionMain {

    private static final int DEFAULT_REQUESTS = 20_000;
    private static final int DEFAULT_CONCURRENCY = Math.max(
        1,
        Runtime.getRuntime().availableProcessors()
    );
    private static final int DEFAULT_WARMUP = 2_000;
    private static final int DEFAULT_RUNS = 3;
    private static final byte[] PING_REQUEST = (
        "GET /ping HTTP/1.1\r\n" +
        "Host: 127.0.0.1\r\n" +
        "Connection: keep-alive\r\n" +
        "\r\n"
    ).getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] PONG_BODY = "pong".getBytes(StandardCharsets.ISO_8859_1);

    public static void main(String[] args) throws Exception {
        new HttpEngineDecisionMain().run();
    }

    private void run() throws Exception {
        String role = System.getProperty("bench.role", "suite");
        if ("server".equalsIgnoreCase(role)) {
            runServer();
            return;
        }
        if ("client".equalsIgnoreCase(role)) {
            runClientRole();
            return;
        }
        runSuite();
    }

    private void runSuite() throws Exception {
        String engineId = System.getProperty("bench.engine", "freeway");
        String mode = System.getProperty("bench.mode", "short");
        int requests = intProperty("bench.requests", DEFAULT_REQUESTS, 1);
        int concurrency = intProperty("bench.concurrency", DEFAULT_CONCURRENCY, 1);
        int warmup = intProperty("bench.warmup", DEFAULT_WARMUP, 0);
        int runs = intProperty("bench.runs", DEFAULT_RUNS, 1);
        int pauseMillis = intProperty("bench.pauseMillis", 5_000, 0);

        List<RunResult> results = new ArrayList<>(runs);
        for (int i = 0; i < runs; i++) {
            RunResult result = runOne(
                engineId,
                mode,
                requests,
                concurrency,
                warmup,
                i + 1,
                runs
            );
            results.add(result);
            System.out.printf(Locale.ROOT, "[run %d/%d] %s%n", i + 1, runs, result);
            if (i + 1 < runs && pauseMillis > 0) {
                Thread.sleep(pauseMillis);
            }
        }

        RunResult median = medianOf(results);
        System.out.printf(Locale.ROOT, "[median] %s%n", median);
    }

    private RunResult runOne(
        String engineId,
        String mode,
        int requests,
        int concurrency,
        int warmup,
        int runIndex,
        int runCount
    ) throws Exception {
        Path serverLog = Files.createTempFile(
            "freeway-bench-server-" + engineId + "-" + runIndex + "-",
            ".log"
        );
        Process server = startProcess(
            List.of(
                javaBinary(),
                "-cp",
                benchmarkClasspath(),
                "-Dbench.role=server",
                "-Dbench.engine=" + engineId,
                "-Dbench.mode=" + mode,
                "com.jujin.freeway.benchmarks.http.HttpEngineDecisionMain"
            ),
            serverLog
        );
        try {
            int port = awaitReady(server, serverLog, Duration.ofSeconds(30));
            Path clientLog = Files.createTempFile(
                "freeway-bench-client-" + engineId + "-" + runIndex + "-",
                ".log"
            );
            Process client = startProcess(
                List.of(
                    javaBinary(),
                    "-cp",
                    benchmarkClasspath(),
                    "-Dbench.role=client",
                    "-Dbench.engine=" + engineId,
                    "-Dbench.mode=" + mode,
                    "-Dbench.requests=" + Integer.toString(requests),
                    "-Dbench.concurrency=" + Integer.toString(concurrency),
                    "-Dbench.warmup=" + Integer.toString(warmup),
                    "-Dbench.port=" + Integer.toString(port),
                    "com.jujin.freeway.benchmarks.http.HttpEngineDecisionMain"
                ),
                clientLog
            );
            try {
                int exitCode = client.waitFor();
                if (exitCode != 0) {
                    throw new IllegalStateException(
                        "Client exited with code " + exitCode + "\n" +
                        Files.readString(clientLog, StandardCharsets.ISO_8859_1)
                    );
                }
                return parseResult(Files.readString(clientLog, StandardCharsets.ISO_8859_1));
            } finally {
                stopProcess(client);
                Files.deleteIfExists(clientLog);
            }
        } finally {
            stopProcess(server);
            Files.deleteIfExists(serverLog);
        }
    }

    private void runServer() throws Exception {
        String engineId = System.getProperty("bench.engine", "freeway");
        if ("robaho-native".equalsIgnoreCase(engineId)) {
            runBareRobahoServer(engineId);
            return;
        }
        if ("jdk-native".equalsIgnoreCase(engineId)) {
            runBareJdkServer(engineId);
            return;
        }
        if ("undertow-native".equalsIgnoreCase(engineId)) {
            runBareUndertowServer(engineId);
            return;
        }
        HttpEngine engine = selectEngine(engineId);

        String mode = System.getProperty("bench.mode", "short");
        boolean wsMode = "ws".equalsIgnoreCase(mode);

        WebSocketIndex wsIndex;
        if (wsMode) {
            wsIndex = new WebSocketIndex(List.of(), List.of(
                com.jujin.freeway.http.websocket.WebSocketGroup.of("/ws",
                    com.jujin.freeway.http.websocket.WebSocketRoute.of("/echo",
                        session -> new com.jujin.freeway.http.websocket.WebSocketListener() {
                            @Override
                            public void onText(String text) throws Exception {
                                session.sendText(text);
                            }
                        })
                )
            ));
        } else {
            wsIndex = new WebSocketIndex(List.of(), List.of());
        }

        var pipeline = new RequestPipeline(
            new RouteIndex(
                List.of(Route.get("/ping", ctx -> ctx.send(200, "pong"))),
                List.of()
            ),
            wsIndex,
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

        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "freeway-bench-server-shutdown"));
        server.start();
        System.out.printf(
            Locale.ROOT,
            "READY port=%d engine=%s%n",
            server.port(),
            engineId
        );
        System.out.flush();
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private void runBareRobahoServer(String engineId) throws Exception {
        DefaultHttpServerProvider provider = new DefaultHttpServerProvider();
        HttpServer server = provider.createHttpServer(new InetSocketAddress("127.0.0.1", 0), 128);
        server.createContext("/", this::handleBareRobahoRequest);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "freeway-bench-robaho-native-shutdown"));
        server.start();

        InetSocketAddress address = server.getAddress();
        System.out.printf(
            Locale.ROOT,
            "READY port=%d engine=%s%n",
            address.getPort(),
            engineId
        );
        System.out.flush();
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private void runBareJdkServer(String engineId) throws Exception {
        // Force JDK's built-in provider even when robaho is on classpath
        System.setProperty("com.sun.net.httpserver.HttpServerProvider",
            "sun.net.httpserver.DefaultHttpServerProvider");
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", this::handleBareRobahoRequest);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "freeway-bench-jdk-native-shutdown"));
            server.start();
            InetSocketAddress address = server.getAddress();
            System.out.printf(Locale.ROOT, "READY port=%d engine=%s%n", address.getPort(), engineId);
            System.out.flush();
            new java.util.concurrent.CountDownLatch(1).await();
        } finally {
            System.clearProperty("com.sun.net.httpserver.HttpServerProvider");
        }
    }

    private void runBareUndertowServer(String engineId) throws Exception {
        Undertow server = Undertow.builder()
            .addHttpListener(0, "127.0.0.1")
            .setHandler(this::handleBareUndertowRequest)
            .build();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "freeway-bench-undertow-native-shutdown"));
        server.start();

        InetSocketAddress address = (InetSocketAddress) server.getListenerInfo().get(0).getAddress();
        System.out.printf(
            Locale.ROOT,
            "READY port=%d engine=%s%n",
            address.getPort(),
            engineId
        );
        System.out.flush();
        new java.util.concurrent.CountDownLatch(1).await();
    }

    private void handleBareRobahoRequest(HttpExchange exchange) throws IOException {
        if (!"/ping".equals(exchange.getRequestURI().getPath())) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=iso-8859-1");
        exchange.sendResponseHeaders(200, PONG_BODY.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(PONG_BODY);
        } finally {
            exchange.close();
        }
    }

    private void handleBareUndertowRequest(HttpServerExchange exchange) throws Exception {
        if (!"/ping".equals(exchange.getRequestPath())) {
            exchange.setStatusCode(StatusCodes.NOT_FOUND);
            exchange.endExchange();
            return;
        }
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain; charset=iso-8859-1");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(PONG_BODY.length));
        exchange.setStatusCode(StatusCodes.OK);
        exchange.getResponseSender().send("pong");
    }

    private void runClientRole() throws Exception {
        String engineId = System.getProperty("bench.engine", "freeway");
        String mode = System.getProperty("bench.mode", "short");
        int requests = intProperty("bench.requests", DEFAULT_REQUESTS, 1);
        int concurrency = intProperty("bench.concurrency", DEFAULT_CONCURRENCY, 1);
        int warmup = intProperty("bench.warmup", DEFAULT_WARMUP, 0);
        int port = intProperty("bench.port", 0, 1);

        RunResult result = switch (mode.toLowerCase(Locale.ROOT)) {
            case "short" -> runLoadShort(engineId, port, requests, concurrency, warmup);
            case "keepalive", "long" -> runLoadKeepAlive(engineId, port, requests, concurrency, warmup);
            case "ws" -> runWebSocketClient(engineId, port, requests, concurrency, warmup);
            default -> throw new IllegalArgumentException("Unknown bench.mode: " + mode);
        };
        System.out.println(result.toResultLine());
    }

    private RunResult runLoadShort(
        String engineId,
        int port,
        int requests,
        int concurrency,
        int warmup
    ) throws Exception {
        for (int i = 0; i < warmup; i++) {
            try (Http11ClientConnection connection = new Http11ClientConnection(openSocket(port))) {
                if (!connection.sendPing()) {
                    throw new IOException("Warmup request failed");
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
            for (int i = 0; i < concurrency; i++) {
                futures.add(workers.submit(() -> {
                    while (true) {
                        int index = next.getAndIncrement();
                        if (index >= requests) {
                            return null;
                        }
                        try (Http11ClientConnection connection = new Http11ClientConnection(openSocket(port))) {
                            long t0 = System.nanoTime();
                            if (connection.sendPing()) {
                                latencies[success.getAndIncrement()] =
                                    (System.nanoTime() - t0) / 1_000L;
                            } else {
                                errors.incrementAndGet();
                            }
                        } catch (Exception ex) {
                            errors.incrementAndGet();
                        }
                    }
                }));
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
        if (errors.get() > 0 || ok != requests) {
            throw new IllegalStateException(
                "Benchmark failed: ok=" + ok + " errors=" + errors.get() + " requests=" + requests
            );
        }
        long[] sample = Arrays.copyOf(latencies, ok);
        Arrays.sort(sample);
        long p50 = percentile(sample, 0.50);
        long p95 = percentile(sample, 0.95);
        long p99 = percentile(sample, 0.99);
        double rps = ok * 1_000_000_000.0 / elapsed;
        return new RunResult(engineId, requests, ok, 0, rps, p50, p95, p99);
    }

    private RunResult runLoadKeepAlive(
        String engineId,
        int port,
        int requests,
        int concurrency,
        int warmup
    ) throws Exception {
        long[] latencies = new long[requests];
        AtomicInteger warmupRemaining = new AtomicInteger(warmup);
        AtomicInteger requestRemaining = new AtomicInteger(requests);
        AtomicInteger recorded = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(concurrency);

        long started = System.nanoTime();
        try {
            List<Future<?>> futures = new ArrayList<>(concurrency);
            for (int i = 0; i < concurrency; i++) {
                futures.add(workers.submit(() ->
                    runWorker(
                        port,
                        warmupRemaining,
                        requestRemaining,
                        latencies,
                        recorded
                    )
                ));
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
        int ok = recorded.get();
        if (ok != requests) {
            throw new IllegalStateException(
                "Benchmark failed: ok=" + ok + " requests=" + requests
            );
        }
        long[] sample = Arrays.copyOf(latencies, ok);
        Arrays.sort(sample);
        long p50 = percentile(sample, 0.50);
        long p95 = percentile(sample, 0.95);
        long p99 = percentile(sample, 0.99);
        double rps = ok * 1_000_000_000.0 / elapsed;
        return new RunResult(engineId, requests, ok, 0, rps, p50, p95, p99);
    }

    private RunResult runWebSocketClient(
        String engineId, int port, int requests, int concurrency, int warmup
    ) throws Exception {
        var client = java.net.http.HttpClient.newHttpClient();
        long[] latencies = new long[requests];
        AtomicInteger latIdx = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        ExecutorService workers = Executors.newFixedThreadPool(concurrency);
        long started = System.nanoTime();
        try {
            List<Future<?>> futures = new ArrayList<>(concurrency);
            for (int t = 0; t < concurrency; t++) {
                futures.add(workers.submit(() -> {
                    try {
                        // Mutable holder for per-message sync — AtomicReference lets
                        // the lambda capture it while the loop replaces the value.
                        var echoRef = new java.util.concurrent.atomic
                            .AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
                        echoRef.set(new java.util.concurrent.CompletableFuture<>());
                        long[] sentNs = new long[1];
                        var ready = new java.util.concurrent.CompletableFuture<Void>();
                        var ws = client.newWebSocketBuilder()
                            .buildAsync(java.net.URI.create(
                                "ws://127.0.0.1:" + port + "/ws/echo"),
                                new java.net.http.WebSocket.Listener() {
                                    @Override
                                    public void onOpen(java.net.http.WebSocket webSocket) {
                                        webSocket.request(Long.MAX_VALUE);
                                        ready.complete(null);
                                    }
                                    @Override
                                    public java.util.concurrent.CompletionStage<?> onText(
                                            java.net.http.WebSocket webSocket,
                                            CharSequence data, boolean last) {
                                        if (sentNs[0] > 0) {
                                            int k = latIdx.getAndIncrement();
                                            if (k < latencies.length) {
                                                latencies[k] = (System.nanoTime() - sentNs[0]) / 1000L;
                                            }
                                            sentNs[0] = 0;
                                        }
                                        // Signal the blocked sender
                                        var f = echoRef.getAndSet(
                                            new java.util.concurrent.CompletableFuture<>());
                                        if (f != null) f.complete(null);
                                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                                    }
                                    @Override
                                    public void onError(java.net.http.WebSocket webSocket,
                                                        Throwable error) {
                                        errors.incrementAndGet();
                                        var f = echoRef.get();
                                        if (f != null) f.completeExceptionally(error);
                                    }
                                }).join();
                        ready.get(10, TimeUnit.SECONDS);

                        // Warmup
                        for (int w = 0; w < warmup / concurrency; w++) {
                            ws.sendText("w", true);
                            echoRef.get().get(3, TimeUnit.SECONDS);
                        }

                        // Measurement — wait for echo before next send
                        for (int m = 0; m < requests / concurrency; m++) {
                            sentNs[0] = System.nanoTime();
                            try {
                                ws.sendText("ping", true);
                                echoRef.get().get(5, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                errors.incrementAndGet();
                                sentNs[0] = 0;
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(120, TimeUnit.SECONDS); }
                catch (Exception e) { errors.incrementAndGet(); }
            }
        } finally {
            workers.shutdownNow();
        }
        Thread.sleep(200);

        long elapsed = System.nanoTime() - started;
        int ok = latIdx.get();
        if (ok <= 0) throw new IllegalStateException("All WS requests failed");
        long[] sample = Arrays.copyOf(latencies, ok);
        Arrays.sort(sample);
        return new RunResult(engineId, requests, ok, errors.get(),
            ok * 1_000_000_000.0 / elapsed,
            percentile(sample, 0.50), percentile(sample, 0.95), percentile(sample, 0.99));
    }

    private static Void runWorker(
        int port,
        AtomicInteger warmupRemaining,
        AtomicInteger requestRemaining,
        long[] latencies,
        AtomicInteger recorded
    ) {
        try (Http11ClientConnection connection = new Http11ClientConnection(openSocket(port))) {
            while (true) {
                int slot = warmupRemaining.getAndDecrement();
                if (slot <= 0) {
                    break;
                }
                if (!connection.sendPing()) {
                    throw new IOException("Warmup request failed");
                }
            }

            while (true) {
                int slot = requestRemaining.getAndDecrement();
                if (slot <= 0) {
                    return null;
                }
                long t0 = System.nanoTime();
                if (!connection.sendPing()) {
                    throw new IOException("Benchmark request failed");
                }
                latencies[recorded.getAndIncrement()] = (System.nanoTime() - t0) / 1_000L;
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Process startProcess(List<String> command, Path logFile) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        return builder.start();
    }

    private static int awaitReady(Process process, Path logFile, Duration timeout)
        throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(logFile)) {
                for (String line : Files.readString(logFile, StandardCharsets.ISO_8859_1).lines().toList()) {
                    if (line.startsWith("READY port=")) {
                        int port = parseReadyPort(line);
                        if (port > 0) {
                            return port;
                        }
                    }
                }
            }
            if (!process.isAlive()) {
                throw new IllegalStateException(
                    "Server exited before readiness:\n" +
                    Files.readString(logFile, StandardCharsets.ISO_8859_1)
                );
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("Timed out waiting for server readiness");
    }

    private static int parseReadyPort(String line) {
        int start = line.indexOf("port=");
        if (start < 0) {
            return -1;
        }
        start += 5;
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        if (end == start) {
            return -1;
        }
        return Integer.parseInt(line.substring(start, end));
    }

    private static RunResult parseResult(String output) {
        for (String line : output.lines().toList()) {
            if (line.startsWith("RESULT ")) {
                return RunResult.parse(line);
            }
        }
        throw new IllegalStateException("Missing RESULT line:\n" + output);
    }

    private static void stopProcess(Process process) {
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static RunResult medianOf(List<RunResult> results) {
        List<RunResult> sortedByRps = results.stream()
            .sorted(Comparator.comparingDouble(RunResult::rps))
            .collect(Collectors.toList());
        return new RunResult(
            sortedByRps.get(sortedByRps.size() / 2).engineId(),
            medianInt(results, RunResult::requests),
            medianInt(results, RunResult::ok),
            medianInt(results, RunResult::errors),
            medianDouble(results, RunResult::rps),
            medianLong(results, RunResult::p50Micros),
            medianLong(results, RunResult::p95Micros),
            medianLong(results, RunResult::p99Micros)
        );
    }

    private static int medianInt(List<RunResult> results, java.util.function.ToIntFunction<RunResult> getter) {
        return results.stream().mapToInt(getter).sorted().skip(results.size() / 2).findFirst().orElse(0);
    }

    private static long medianLong(List<RunResult> results, java.util.function.ToLongFunction<RunResult> getter) {
        return results.stream().mapToLong(getter).sorted().skip(results.size() / 2).findFirst().orElse(0L);
    }

    private static double medianDouble(List<RunResult> results, java.util.function.ToDoubleFunction<RunResult> getter) {
        return results.stream().mapToDouble(getter).sorted().skip(results.size() / 2).findFirst().orElse(0.0d);
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
            default -> throw new IllegalArgumentException("Unknown bench.engine: " + engineId);
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

    private static String benchmarkClasspath() throws IOException {
        String override = System.getProperty("bench.classpath");
        if (override != null && !override.isBlank()) {
            return moduleClassesDir() + System.getProperty("path.separator") + override;
        }
        for (String candidate : List.of(
            "freeway-benchmarks/target/benchmark.classpath",
            "target/benchmark.classpath"
        )) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                String deps = Files.readString(path, StandardCharsets.ISO_8859_1).trim();
                if (!deps.isBlank()) {
                    return moduleClassesDir() + System.getProperty("path.separator") + deps;
                }
            }
        }
        throw new IllegalStateException(
            "Missing benchmark.classpath. Run mvn -pl freeway-benchmarks -am test -DskipTests " +
            "or pass -Dbench.classpath=<explicit classpath>"
        );
    }

    private static String moduleClassesDir() {
        for (String candidate : List.of(
            "freeway-benchmarks/target/classes",
            "target/classes"
        )) {
            Path path = Path.of(candidate);
            if (Files.isDirectory(path)) {
                return path.toString();
            }
        }
        return Path.of("freeway-benchmarks/target/classes").toString();
    }

    private static String javaBinary() {
        String javaHome = System.getProperty("java.home");
        Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
        return java.toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static int intProperty(String name, int defaultValue, int minValue) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value = Integer.parseInt(raw.trim());
        if (value < minValue) {
            throw new IllegalArgumentException(name + " must be >= " + minValue + ": " + value);
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

    private record RunResult(
        String engineId,
        int requests,
        int ok,
        int errors,
        double rps,
        long p50Micros,
        long p95Micros,
        long p99Micros
    ) {
        private static RunResult parse(String line) {
            String[] parts = line.substring("RESULT ".length()).split(" ");
            String engineId = value(parts, "engine");
            int requests = Integer.parseInt(value(parts, "requests"));
            int ok = Integer.parseInt(value(parts, "ok"));
            int errors = Integer.parseInt(value(parts, "errors"));
            double rps = Double.parseDouble(value(parts, "rps"));
            long p50 = Long.parseLong(value(parts, "p50"));
            long p95 = Long.parseLong(value(parts, "p95"));
            long p99 = Long.parseLong(value(parts, "p99"));
            return new RunResult(engineId, requests, ok, errors, rps, p50, p95, p99);
        }

        private String toResultLine() {
            return String.format(
                Locale.ROOT,
                "RESULT engine=%s requests=%d ok=%d errors=%d rps=%.0f p50=%d p95=%d p99=%d",
                engineId,
                requests,
                ok,
                errors,
                rps,
                p50Micros,
                p95Micros,
                p99Micros
            );
        }

        @Override
        public String toString() {
            return String.format(
                Locale.ROOT,
                "engine=%s requests=%d ok=%d errors=%d rps=%.0f p50=%dus p95=%dus p99=%dus",
                engineId,
                requests,
                ok,
                errors,
                rps,
                p50Micros,
                p95Micros,
                p99Micros
            );
        }

        private static String value(String[] parts, String key) {
            for (String part : parts) {
                if (part.startsWith(key + "=")) {
                    return part.substring(key.length() + 1);
                }
            }
            throw new IllegalArgumentException("Missing key " + key);
        }
    }

    private static final class Http11ClientConnection implements AutoCloseable {

        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        private Http11ClientConnection(Socket socket) throws IOException {
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

        @Override
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
