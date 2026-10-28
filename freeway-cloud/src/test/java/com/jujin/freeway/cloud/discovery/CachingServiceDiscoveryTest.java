package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.Endpoint;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The freshness contract of a caching discovery implementation: answers are
 * reused for the TTL, one refresh happens per expiry (not one per caller), a
 * failing backend is bridged by the last answer within the grace window, and the
 * per-service override can shorten the window or opt out entirely.
 */
class CachingServiceDiscoveryTest {

    private static final ServiceInstance INSTANCE =
        ServiceInstance.of("svc", "i1", Endpoint.of("http", "127.0.0.1", 8080));

    /** Counts backend calls; fails on demand. */
    private static final class Backend implements ServiceDiscovery {
        final AtomicInteger calls = new AtomicInteger();
        volatile List<ServiceInstance> answer = List.of(INSTANCE);
        volatile RuntimeException failure;
        volatile long delayMillis;

        @Override
        public List<ServiceInstance> instances(String serviceId) {
            calls.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private static CachingServiceDiscovery caching(ServiceDiscovery delegate, Duration ttl) {
        return new CachingServiceDiscovery(delegate, serviceId -> ttl, null);
    }

    /** Records counter increments without a metrics backend. */
    private static final class CountingMetrics implements com.jujin.freeway.commons.metrics.Metrics {
        final java.util.Map<String, Long> counts = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Counter counter(String name) {
            return new Counter() {
                @Override
                public void increment() {
                    add(1);
                }

                @Override
                public void add(long delta) {
                    counts.merge(name, delta, Long::sum);
                }

                @Override
                public long value() {
                    return counts.getOrDefault(name, 0L);
                }
            };
        }

        @Override
        public void gauge(String name, java.util.function.Supplier<Number> supplier) {
            // not used by the cache
        }
    }

    @Test
    void the_freshness_outcomes_are_counted() {
        Backend backend = new Backend();
        CountingMetrics metrics = new CountingMetrics();
        CachingServiceDiscovery cache = new CachingServiceDiscovery(
            backend, serviceId -> Duration.ofMillis(20), metrics);

        cache.instances("svc");                          // refresh
        cache.instances("svc");                          // hit
        backend.failure = new IllegalStateException("down");
        try {
            Thread.sleep(30);
            cache.instances("svc");                      // stale (served from cache)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        backend.failure = null;                          // the backend recovers
        CachingServiceDiscovery bypass = new CachingServiceDiscovery(
            backend, serviceId -> Duration.ZERO, metrics);
        bypass.instances("other");                       // bypass (ttl 0)

        assertEquals(1L, metrics.counts.getOrDefault("cloud.registry.cache.hits", 0L),
            "a reused answer is a hit: " + metrics.counts);
        assertEquals(2L, metrics.counts.getOrDefault("cloud.registry.cache.refreshes", 0L),
            "the initial refresh plus the attempt that found the backend failing: "
                + metrics.counts);
        assertEquals(1L, metrics.counts.getOrDefault("cloud.registry.cache.stale", 0L),
            "serving the old answer during a failure is counted as stale: " + metrics.counts);
        assertEquals(1L, metrics.counts.getOrDefault("cloud.registry.cache.bypass", 0L),
            "a zero TTL asks every time and is counted as bypass: " + metrics.counts);
    }

    @Test
    void an_answer_is_reused_within_the_ttl() {
        Backend backend = new Backend();
        CachingServiceDiscovery cache = caching(backend, Duration.ofSeconds(5));

        cache.instances("svc");
        cache.instances("svc");
        cache.instances("svc");

        assertEquals(1, backend.calls.get(), "one backend call serves every reuse");
    }

    @Test
    void an_expired_answer_is_refreshed_once_for_every_waiting_caller() throws Exception {
        Backend backend = new Backend();
        backend.delayMillis = 50;   // widen the window so waiters pile up
        CachingServiceDiscovery cache = caching(backend, Duration.ofMillis(30));

        cache.instances("svc");     // prime, then let it expire
        Thread.sleep(60);

        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        for (int i = 0; i < callers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    cache.instances("svc");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(2, backend.calls.get(),
            "one refresh for the expiry, not one per caller — got " + backend.calls.get());
    }

    @Test
    void a_failing_backend_is_bridged_by_the_last_answer() {
        Backend backend = new Backend();
        CachingServiceDiscovery cache = caching(backend, Duration.ofMillis(50));
        cache.instances("svc");

        backend.failure = new IllegalStateException("registry fleet unreachable");
        List<ServiceInstance> served = cache.instances("svc");

        assertEquals(List.of(INSTANCE), served,
            "inside the grace window the stale answer beats a failed call");
    }

    @Test
    void a_stale_answer_outlives_the_ttl_window_within_the_grace() throws Exception {
        // The TTL says "ask again"; the grace says "if asking fails, the old
        // answer still stands". This pins the second half: past the TTL and
        // inside the 30s grace, a failing backend still serves the old answer.
        Backend backend = new Backend();
        CachingServiceDiscovery cache = new CachingServiceDiscovery(backend, serviceId -> Duration.ofMillis(5), null);
        cache.instances("svc");

        backend.failure = new IllegalStateException("registry fleet unreachable");
        Thread.sleep(20);
        assertEquals(List.of(INSTANCE), cache.instances("svc"),
            "the grace window outlives the TTL on purpose");
    }

    @Test
    void grace_is_at_least_the_registry_eviction_horizon() throws Exception {
        // Fallback stays servable while the registry itself would still hold
        // the instance (30s eviction): a short TTL must not make the cache
        // stricter than the registry about what "gone" means.
        java.lang.reflect.Method grace =
            CachingServiceDiscovery.class.getDeclaredMethod("graceMillis", Duration.class);
        grace.setAccessible(true);
        assertEquals(30_000L, (long) (Long) grace.invoke(null, Duration.ofMillis(100)));
        assertEquals(60_000L, (long) (Long) grace.invoke(null, Duration.ofSeconds(6)));
    }

    @Test
    void an_empty_answer_is_cached_like_any_other() {
        Backend backend = new Backend();
        backend.answer = List.of();
        CachingServiceDiscovery cache = caching(backend, Duration.ofSeconds(5));

        assertTrue(cache.instances("svc").isEmpty());
        assertTrue(cache.instances("svc").isEmpty());

        assertEquals(1, backend.calls.get(),
            "'no instances' is an answer, not a miss to re-ask on every call");
    }

    @Test
    void a_zero_ttl_asks_the_backend_every_time() {
        Backend backend = new Backend();
        CachingServiceDiscovery cache = caching(backend, Duration.ZERO);

        cache.instances("svc");
        cache.instances("svc");

        assertEquals(2, backend.calls.get(), "ttl 0 is the per-call behaviour");
    }

    @Test
    void ttl_is_resolved_per_service() {
        Backend backend = new Backend();
        CachingServiceDiscovery cache = new CachingServiceDiscovery(backend,
            serviceId -> serviceId.equals("fresh") ? Duration.ZERO : Duration.ofSeconds(5), null);

        cache.instances("fresh");
        cache.instances("fresh");
        cache.instances("steady");
        cache.instances("steady");

        assertEquals(3, backend.calls.get(),
            "the per-service override applies to its own id only (fresh=2 calls, steady=1)");
    }

    @Test
    void a_backend_failure_with_no_previous_answer_propagates() {
        Backend backend = new Backend();
        backend.failure = new IllegalStateException("registry fleet unreachable");
        CachingServiceDiscovery cache = caching(backend, Duration.ofSeconds(5));

        assertThrows(IllegalStateException.class, () -> cache.instances("svc"),
            "with nothing to fall back to, the failure must be the answer");
    }
}
