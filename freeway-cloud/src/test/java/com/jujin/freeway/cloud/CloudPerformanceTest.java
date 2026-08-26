package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.CircuitBreakerDefault;
import com.jujin.freeway.cloud.internal.RateLimiterDefault;
import com.jujin.freeway.cloud.internal.RegistryStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lightweight throughput smoke benchmarks for freeway-cloud hot paths.
 *
 * <p>Not a substitute for JMH: each scenario warms up, runs a fixed number of
 * operations (single- or multi-threaded), and prints {@code ops/s}. The
 * assertions are deliberately <b>sanity floors</b> (three orders of magnitude
 * below what commodity hardware delivers) so the run never fails on a loaded
 * or throttled CI machine — the printed numbers are the real output; run with
 * {@code -Dtest=CloudPerformanceTest} and read the console. Raise the floor
 * temporarily (or assert on it) when profiling a regression locally.
 */
class CloudPerformanceTest {

    /**
     * Sanity floor applied uniformly: low enough for the most throttled CI
     * runner, high enough to catch a pathological accidental O(n) or lock
     * storm (those collapse throughput by orders of magnitude, not by 2x).
     */
    private static final double FLOOR_OPS_PER_SECOND =
        Double.parseDouble(System.getProperty("cloud.bench.floor", "1_000")
            .replace("_", ""));

    // ── Circuit breaker: CLOSED success path ────────────────

    @Test
    void circuitBreakerClosedPathThroughput() throws Exception {
        CircuitBreakerDefault breaker = new CircuitBreakerDefault(
            5, Duration.ofSeconds(60), Duration.ofSeconds(30));
        double ops = measure(4, 500_000, () -> {
            if (!breaker.allowRequest()) {
                throw new AssertionError("closed circuit must admit");
            }
            breaker.onSuccess();
        });
        print("CircuitBreaker CLOSED allowRequest+onSuccess (4 threads)", ops);
        assertTrue(ops > FLOOR_OPS_PER_SECOND, "got " + (long) ops);
    }

    /** The per-service shard lookup the CloudHttpClient performs per call. */
    @Test
    void perServiceShardLookupThroughput() throws Exception {
        var shards = new java.util.concurrent.ConcurrentHashMap<String, CircuitBreakerDefault>();
        // Consume the admit decision so the JIT cannot eliminate the loop.
        var admitted = new AtomicInteger();
        double ops = measure(4, 500_000, () -> {
            CircuitBreakerDefault breaker = shards.computeIfAbsent("svc-a", k ->
                new CircuitBreakerDefault(5, Duration.ofSeconds(60), Duration.ofSeconds(30)));
            if (!breaker.allowRequest()) {
                throw new AssertionError("closed circuit must admit");
            }
            admitted.incrementAndGet();
        });
        print("per-service breaker shard lookup+admit (4 threads)", ops);
        assertTrue(ops > FLOOR_OPS_PER_SECOND, "got " + (long) ops);
    }

    // ── Rate limiter: token bucket ──────────────────────────

    @Test
    void rateLimiterCallThroughput() throws Exception {
        // Measures the per-call cost of the synchronized token bucket,
        // regardless of admit/reject outcome (a tight loop can land two
        // nanoTime reads on the same clock tick, making the refill 0 — an
        // artifact of unbounded loop speed, not a production path; the call
        // cost itself is what this benchmark compares).
        RateLimiterDefault limiter = new RateLimiterDefault(1.0);
        var calls = new AtomicInteger();
        double ops = measure(4, 250_000, () -> {
            limiter.tryAcquire();
            calls.incrementAndGet();
        });
        print("RateLimiter tryAcquire call cost (4 threads, synchronized)", ops);
        assertTrue(ops > FLOOR_OPS_PER_SECOND, "got " + (long) ops);
    }

    // ── Trace context: id generation per request ────────────

    @Test
    void traceContextGenerationThroughput() throws Exception {
        double root = measure(4, 500_000, () -> {
            TraceContext ctx = TraceContext.root();
            if (ctx.traceId() == null) {
                throw new AssertionError();
            }
        });
        print("TraceContext.root() (4 threads)", root);
        assertTrue(root > FLOOR_OPS_PER_SECOND, "got " + (long) root);
    }

    // ── Registry: register + live-ready query ───────────────

    @Test
    void registryRegisterAndQueryThroughput() throws Exception {
        RegistryStore store = new RegistryStore();
        double ops = measure(4, 200_000, () -> {
            store.register(ServiceInstance.of("svc", "i1",
                Endpoint.of("http", "127.0.0.1", 8080), Map.of()));
            if (store.liveReady("svc", Duration.ofSeconds(30)).isEmpty()) {
                throw new AssertionError("freshly registered instance must be live");
            }
        });
        print("RegistryStore register+liveReady (4 threads)", ops);
        assertTrue(ops > FLOOR_OPS_PER_SECOND, "got " + (long) ops);
    }

    // ── harness ─────────────────────────────────────────────

    private static double measure(int threads, int iterationsPerThread, Runnable task)
            throws InterruptedException {
        // Warm-up: JIT-compile the path, populate caches.
        for (int i = 0; i < 20_000; i++) {
            task.run();
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch done = new CountDownLatch(threads);
            long start = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < iterationsPerThread; i++) {
                            task.run();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
            long elapsedNanos = System.nanoTime() - start;
            return threads * (double) iterationsPerThread / (elapsedNanos / 1e9);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void print(String name, double opsPerSecond) {
        System.out.printf("[cloud-bench] %-55s %,12.0f ops/s%n",
            name, opsPerSecond);
    }
}
