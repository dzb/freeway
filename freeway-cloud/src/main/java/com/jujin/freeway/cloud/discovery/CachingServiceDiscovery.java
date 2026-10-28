package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.commons.metrics.Metrics;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A discovery implementation that caches its answers — the normal shape, not a
 * special variant: every production discovery client caches (Eureka 30s,
 * Ribbon 30s, Spring Cloud LoadBalancer 35s; push-based ones like Kubernetes
 * informers need no TTL at all).
 *
 * <p><b>Where it sits.</b> Freshness is a property of the backend, so the cache
 * lives inside the discovery implementation: every consumer — remote calls, the
 * mesh peer feeder, an application's own {@code @Inject ServiceDiscovery} —
 * gets it without knowing about it, and the seam stays one method. A cached
 * answer is at most the TTL old, which is far inside the registry's own
 * staleness window; the load balancer's rotation is what keeps a stale entry
 * from absorbing more than one attempt.</p>
 *
 * <p><b>Failure policy.</b> A backend that cannot answer is exactly when the
 * last answer is worth most: within a grace window the cached view is served
 * and the failure logged. Beyond it the failure propagates — "the registry is
 * gone" must not be disguised as a stale success forever.</p>
 *
 * <p><b>Policy — who wraps what.</b> Caching is the default capability of a
 * discovery implementation, expressed by the implementation wrapping itself at
 * its own binding (a {@code .primary()} binding replaces the default wholesale,
 * so a framework-side wrapper would only ever reach the in-process store):</p>
 *
 * <ul>
 *   <li>an implementation that talks to the network wraps itself —
 *       {@code RegistryFleetModule} does it for the fleet, and an adapter such
 *       as Consul does the same in its own module;</li>
 *   <li>the in-process store ({@link ServiceDiscoveryDefault}) does not wrap:
 *       its answer is a map read, and cache bookkeeping would make it
 *       slower;</li>
 *   <li>a backend whose freshness is pushed (a Kubernetes informer) does not
 *       wrap, or answers {@link Duration#ZERO} from its {@link TtlPolicy}.</li>
 * </ul>
 *
 * <pre>{@code
 * // an adapter binding its own discovery, at the adapter's composition root
 * b.bind(ServiceDiscovery.class)
 *     .to(c -> CachingServiceDiscovery.around(
 *             new ConsulDiscovery(c.get(ConsulClient.class)),
 *             serviceId -> ttlFrom(c, serviceId)))   // TtlPolicy
 *     .primary();
 * }</pre>
 */
public final class CachingServiceDiscovery implements ServiceDiscovery {

    private static final Logger LOG = LoggerFactory.getLogger(CachingServiceDiscovery.class);

    /** Per-service freshness; {@link Duration#ZERO} means "cache nothing". */
    @FunctionalInterface
    public interface TtlPolicy {
        Duration ttlFor(String serviceId);
    }

    /** One cached answer and when it was taken (nanoTime: monotonic). */
    private record Snapshot(List<ServiceInstance> instances, long takenAtNanos) {}

    private final ServiceDiscovery delegate;
    private final TtlPolicy ttl;
    private final Metrics metrics;                      // nullable
    private final ConcurrentHashMap<String, Snapshot> byService = new ConcurrentHashMap<>();
    /** One monitor per service id: a TTL expiry causes one refresh, not one per
     *  waiting caller (the thundering herd a shared cache is supposed to
     *  prevent). */
    private final ConcurrentHashMap<String, Object> refreshLocks = new ConcurrentHashMap<>();

    /**
     * A discovery whose answers are reused for {@code ttl} — the framework's
     * standard way to make a network-backed discovery cheap. A backend wraps
     * itself once, at its own binding, so every consumer sees the caching view
     * without knowing about it.
     */
    public static CachingServiceDiscovery around(
            ServiceDiscovery delegate, TtlPolicy ttl, Metrics metrics) {
        return new CachingServiceDiscovery(delegate, ttl, metrics);
    }

    /** Same, without metrics. */
    public static CachingServiceDiscovery around(ServiceDiscovery delegate, TtlPolicy ttl) {
        return new CachingServiceDiscovery(delegate, ttl, null);
    }

    CachingServiceDiscovery(ServiceDiscovery delegate, TtlPolicy ttl, Metrics metrics) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.metrics = metrics;
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        Duration freshness = ttl.ttlFor(serviceId);
        if (freshness.isZero()) {
            count("cloud.registry.cache.bypass");
            return delegate.instances(serviceId);
        }

        Snapshot cached = byService.get(serviceId);
        if (cached != null && ageMillis(cached) < freshness.toMillis()) {
            count("cloud.registry.cache.hits");
            return cached.instances;
        }

        Object lock = refreshLocks.computeIfAbsent(serviceId, key -> new Object());
        synchronized (lock) {
            // Another caller may have refreshed while this one waited.
            Snapshot current = byService.get(serviceId);
            if (current != null && ageMillis(current) < freshness.toMillis()) {
                count("cloud.registry.cache.hits");
                return current.instances;
            }
            count("cloud.registry.cache.refreshes");
            try {
                List<ServiceInstance> fresh = List.copyOf(delegate.instances(serviceId));
                byService.put(serviceId, new Snapshot(fresh, System.nanoTime()));
                return fresh;
            } catch (RuntimeException failed) {
                Snapshot previous = byService.get(serviceId);
                if (previous != null && ageMillis(previous) < graceMillis(freshness)) {
                    // The last answer is worth most exactly when the backend
                    // cannot answer. One warn per outage, not per call: the log
                    // is a cause, and the counter is the cost.
                    count("cloud.registry.cache.stale");
                    LOG.warn("Discovery for '{}' failed — serving the {}ms-old answer: {}",
                        serviceId, ageMillis(previous), failed.getMessage());
                    return previous.instances;
                }
                throw failed;
            }
        }
    }

    /**
     * How long a stale answer may stand in for a failing backend: ten times the
     * freshness window, floor 30s — the registry's own eviction horizon, so a
     * backend that is coming back is not reported as gone sooner than the
     * registry itself would drop its instances.
     */
    private static long graceMillis(Duration freshness) {
        return Math.max(freshness.toMillis() * 10, 30_000L);
    }

    private static long ageMillis(Snapshot snapshot) {
        return (System.nanoTime() - snapshot.takenAtNanos()) / 1_000_000L;
    }

    private void count(String name) {
        if (metrics != null) {
            metrics.counter(name).increment();
        }
    }
}
