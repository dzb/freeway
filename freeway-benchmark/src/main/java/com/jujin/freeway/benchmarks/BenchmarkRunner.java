package com.jujin.freeway.benchmarks;

import com.jujin.freeway.benchmarks.client.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Benchmark runner. Defaults to fork mode: server + client in separate JVMs.
 * <pre>
 * mvn -pl freeway-benchmark process-classes
 * mvn -pl freeway-benchmark exec:java -Dexec.mainClass=...BenchmarkRunner
 *   -Dbench.engine=freeway -Dbench.mode=keepalive -Dbench.requests=2000 -Dbench.runs=3
 * </pre>
 * Set {@code -Dbench.fork=false} to run in-process.
 */
public final class BenchmarkRunner {

    public static void main(String[] args) throws Exception {
        String role = p("bench.role", "suite");
        if ("server".equalsIgnoreCase(role)) { runServer(p("bench.engine","freeway"), p("bench.mode","keepalive")); return; }
        if ("client".equalsIgnoreCase(role)) { runClient(p("bench.engine","freeway"), p("bench.mode","keepalive"), ip("bench.port",0), ip("bench.requests",2000), ip("bench.concurrency",2), ip("bench.warmup",200)); return; }

        String engine = p("bench.engine", "freeway");
        String mode = p("bench.mode", "keepalive");
        int requests = ip("bench.requests", 20_000);
        int concurrency = ip("bench.concurrency", Math.max(1, Runtime.getRuntime().availableProcessors()));
        int warmup = ip("bench.warmup", 2_000);
        int runs = ip("bench.runs", 3);
        int pause = ip("bench.pauseMillis", 3000);
        boolean fork = !"false".equalsIgnoreCase(p("bench.fork", "true"));

        System.out.printf("=== %s mode=%s requests=%d concurrency=%d runs=%d fork=%s ===%n",
            engine, mode, requests, concurrency, runs, fork);

        List<Result> results = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            Result r = fork ? runFork(engine, mode, requests, concurrency, warmup, i)
                            : runInProcess(engine, mode, requests, concurrency, warmup);
            results.add(r);
            System.out.printf("[run %d/%d] %s%n", i + 1, runs, r);
            if (i + 1 < runs && pause > 0) Thread.sleep(pause);
        }
        if (runs > 1) System.out.printf("[median] %s%n", Result.median(results));
    }

    // --- fork mode: separate JVMs ---

    private static Result runFork(String engine, String mode, int requests, int concurrency, int warmup, int runIdx) throws Exception {
        Path serverLog = Files.createTempFile("bench-server-", ".log");
        // Start server in child JVM
        Process server = new ProcessBuilder(javaBin(), "-cp", classpath(),
            "com.jujin.freeway.benchmarks.BenchmarkRunner",
            "-Dbench.role=server", "-Dbench.engine=" + engine, "-Dbench.mode=" + mode)
            .redirectErrorStream(true).redirectOutput(serverLog.toFile()).start();
        try {
            int port = awaitReady(server, serverLog, Duration.ofSeconds(30));
            // Start client in child JVM
            Path clientLog = Files.createTempFile("bench-client-", ".log");
            Process client = new ProcessBuilder(javaBin(), "-cp", classpath(),
                "com.jujin.freeway.benchmarks.BenchmarkRunner",
                "-Dbench.role=client", "-Dbench.engine=" + engine, "-Dbench.mode=" + mode,
                "-Dbench.port=" + port, "-Dbench.requests=" + requests,
                "-Dbench.concurrency=" + concurrency, "-Dbench.warmup=" + warmup)
                .redirectErrorStream(true).redirectOutput(clientLog.toFile()).start();
            try {
                int exit = client.waitFor();
                if (exit != 0) throw new RuntimeException("Client exit " + exit + "\n" + Files.readString(clientLog));
                return parseResult(Files.readString(clientLog));
            } finally { client.destroyForcibly(); Files.deleteIfExists(clientLog); }
        } finally { server.destroyForcibly(); Files.deleteIfExists(serverLog); }
    }

    private static int awaitReady(Process p, Path log, Duration timeout) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(log)) {
                for (String line : Files.readString(log, StandardCharsets.ISO_8859_1).lines().toList()) {
                    if (line.startsWith("READY ")) {
                        String port = line.substring(line.indexOf('=') + 1).split("\\s")[0];
                        return Integer.parseInt(port);
                    }
                }
            }
            if (!p.isAlive()) throw new RuntimeException("Server died: " + Files.readString(log));
            Thread.sleep(100);
        }
        throw new RuntimeException("Server not ready: " + Files.readString(log));
    }

    private static Result parseResult(String output) {
        for (String line : output.lines().toList())
            if (line.startsWith("RESULT ")) return Result.fromLine(line);
        throw new RuntimeException("No RESULT line in output:\n" + output);
    }

    // --- in-process mode ---

    private static Result runInProcess(String engine, String mode, int requests, int concurrency, int warmup) throws Exception {
        try (var h = ServerHarness.start(engine, mode)) {
            return "ws".equalsIgnoreCase(mode)
                ? benchWs(engine, mode, h.port(), requests, concurrency, warmup)
                : benchHttp(engine, mode, h.port(), requests, concurrency, warmup);
        }
    }

    // --- server/client subcommands ---

    private static void runServer(String engine, String mode) throws Exception {
        try (var h = ServerHarness.start(engine, mode)) {
            System.out.println("READY port=" + h.port() + " engine=" + engine);
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE); // wait until killed
        }
    }

    private static void runClient(String engine, String mode, int port, int requests, int concurrency, int warmup) throws Exception {
        Result r = "ws".equalsIgnoreCase(mode)
            ? benchWs(engine, mode, port, requests, concurrency, warmup)
            : benchHttp(engine, mode, port, requests, concurrency, warmup);
        System.out.println("RESULT " + r);
    }

    // --- benchmark logic (shared) ---

    private static Result benchHttp(String engine, String mode, int port, int requests, int concurrency, int warmup) throws Exception {
        boolean keepAlive = !"short".equalsIgnoreCase(mode);
        long[] lats = new long[requests];
        AtomicInteger next = new AtomicInteger(), errors = new AtomicInteger();
        ExecutorService w = Executors.newFixedThreadPool(concurrency);
        long t0 = System.nanoTime();
        try {
            Future<?>[] tasks = new Future<?>[concurrency];
            for (int t = 0; t < concurrency; t++) {
                tasks[t] = w.submit(() -> {
                    try {
                        if (keepAlive) {
                            try (var c = new Http11Client(port)) {
                                while (true) { int i = next.getAndIncrement(); if (i >= requests) break; long ts = System.nanoTime(); if (c.sendPing()) lats[i] = (System.nanoTime() - ts) / 1000L; else errors.incrementAndGet(); }
                            }
                        } else {
                            while (true) { int i = next.getAndIncrement(); if (i >= requests) break; try (var c = new Http11Client(port)) { long ts = System.nanoTime(); if (c.sendPing()) lats[i] = (System.nanoTime() - ts) / 1000L; else errors.incrementAndGet(); } }
                        }
                    } catch (Exception e) { errors.incrementAndGet(); }
                    return null;
                });
            }
            for (var t : tasks) t.get(120, TimeUnit.SECONDS);
        } finally { w.shutdownNow(); }
        return build(engine, mode, requests, errors.get(), lats, t0);
    }

    private static Result benchWs(String engine, String mode, int port, int requests, int concurrency, int warmup) throws Exception {
        long[] lats = new long[requests];
        AtomicInteger next = new AtomicInteger(), errors = new AtomicInteger();
        ExecutorService w = Executors.newFixedThreadPool(concurrency);
        long t0 = System.nanoTime();
        try {
            Future<?>[] tasks = new Future<?>[concurrency];
            for (int t = 0; t < concurrency; t++) {
                tasks[t] = w.submit(() -> {
                    try (var c = new WsClient(port)) {
                        for (int j = 0; j < warmup / concurrency; j++) c.echo("w");
                        while (true) { int i = next.getAndIncrement(); if (i >= requests) break; try { lats[i] = c.echo("ping") / 1000L; } catch (Exception e) { errors.incrementAndGet(); } }
                    } catch (Exception e) { errors.incrementAndGet(); }
                    return null;
                });
            }
            for (var t : tasks) t.get(120, TimeUnit.SECONDS);
        } finally { w.shutdownNow(); }
        return build(engine, mode, requests, errors.get(), lats, t0);
    }

    private static Result build(String e, String m, int req, int errs, long[] lats, long t0) {
        int ok = req - errs;
        long[] s = Arrays.copyOf(lats, ok); Arrays.sort(s);
        return new Result(e, m, req, ok, errs, ok * 1e9 / (System.nanoTime() - t0),
            Result.percentile(s, 0.50), Result.percentile(s, 0.95), Result.percentile(s, 0.99));
    }

    // --- helpers ---

    private static String p(String k, String d) { String v = System.getProperty(k); return v != null && !v.isBlank() ? v : d; }
    private static int ip(String k, int d) { String v = System.getProperty(k); return v == null || v.isBlank() ? d : Integer.parseInt(v.trim()); }

    private static String javaBin() { return ProcessHandle.current().info().command().orElse("java"); }

    private static String classpath() {
        String override = System.getProperty("bench.classpath");
        if (override != null && !override.isBlank()) return override;
        // Default: use Maven-generated classpath file
        for (String p : List.of("freeway-benchmark/target/benchmark.classpath", "target/benchmark.classpath")) {
            Path f = Path.of(p);
            if (Files.isRegularFile(f)) {
                try {
                    String deps = Files.readString(f).trim();
                    String classes = f.getParent().resolveSibling("classes").toString();
                    return classes + File.pathSeparator + deps;
                } catch (IOException ignored) {}
            }
        }
        return System.getProperty("java.class.path");
    }
}
