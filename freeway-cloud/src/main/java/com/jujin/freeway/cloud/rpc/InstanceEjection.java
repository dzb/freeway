package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.resilience.ResilienceModule;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-instance passive ejection (outlier detection): a member that is listed by
 * discovery but keeps failing its own share of calls is taken out of this
 * node's rotation for a while, using only outcomes that already happened — no
 * probing traffic.
 *
 * <p><b>Only the instance's own wire failures count.</b> The classification
 * reads {@link RpcException#kind()}: {@code CONNECT}, {@code TIMEOUT} with a
 * cause (the send happened and the configured request timeout, not the
 * caller's remaining budget, was the bound — a caller-bounded timeout carries
 * no cause), {@code TRANSPORT} and 5xx count; business errors, 4xx, 429,
 * version/schema rejections, local rejections and the deadline-exhausted
 * timeout the client throws before a send (no cause) do not. A kind-based
 * classifier is why the state lives here and not in a {@code LoadBalancer}
 * decorator: only the transport layer knows why a call failed.</p>
 *
 * <p><b>State, per {@code (serviceId, instanceId)}:</b> consecutive qualifying
 * failures (reset by any success), how many times in a row the instance was
 * ejected (reset by a success), and the ejection deadline. The first ejection
 * lasts {@code base-window}, a repeat doubles the window up to
 * {@code max-window}; after the window normal traffic is the probe — a
 * still-broken instance needs {@code failure-threshold} new consecutive
 * failures, not one. Two guards keep a partial topology from becoming an
 * outage: at most {@code max-percent} of the current answer may be ejected,
 * and when the filter would exclude everything it returns the full answer.</p>
 *
 * <p>State is per client node (no coordination — the Envoy outlier-detection
 * model) and bounded: the service map is capped like the breaker shards, and a
 * per-service instance map prunes idle, non-ejected entries. Entries for
 * instances that leave discovery simply go idle and are pruned.</p>
 *
 * <p><b>The steady-state attempt path allocates nothing.</b> The filtered view
 * is memoized per service and reused while the discovery answer is the same
 * immutable snapshot, the state version is unchanged (ejections and recoveries
 * bump it) and no ejection window has expired — the three conditions that can
 * change the answer. A miss recomputes once; discovery snapshots are stable
 * between refreshes by the framework's caching convention, so misses are rare
 * events, not the common path.</p>
 */
final class InstanceEjection {

    private static final Logger LOG = LoggerFactory.getLogger(InstanceEjection.class);

    /** Same bound as the breaker shards: service ids come from discovery. */
    private static final int MAX_SERVICES = 1 << 10;
    /** Instance ids also come from discovery; the map keeps a bounded history. */
    private static final int MAX_INSTANCES_PER_SERVICE = 256;
    /** How many max-windows an idle, non-ejected entry survives before pruning. */
    private static final int IDLE_WINDOWS = 10;

    private static final SymbolSpec<Boolean> ENABLED = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_EJECTION_ENABLED, Boolean.class, true);
    private static final SymbolSpec<Integer> FAILURE_THRESHOLD = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_EJECTION_FAILURE_THRESHOLD, Integer.class,
        ResilienceModule.ConfigKeys.RPC_EJECTION_FAILURE_THRESHOLD_DEFAULT, Integer::parseInt);
    private static final SymbolSpec<Long> BASE_WINDOW = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW, Long.class,
        ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Long> MAX_WINDOW = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_WINDOW, Long.class,
        ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_WINDOW_DEFAULT, Long::parseLong);
    private static final SymbolSpec<Integer> MAX_PERCENT = SymbolSpec.of(
        ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_PERCENT, Integer.class,
        ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_PERCENT_DEFAULT, Integer::parseInt);

    private final boolean enabled;
    private final int failureThreshold;
    private final long baseWindowNanos;
    private final long maxWindowNanos;
    private final int maxPercent;
    private final int maxInstancesPerService;
    private final Metrics metrics;                 // nullable
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, ServiceEjection> services = new ConcurrentHashMap<>();

    InstanceEjection(boolean enabled, int failureThreshold, Duration baseWindow,
                     Duration maxWindow, int maxPercent, Metrics metrics, LongSupplier clock) {
        this(enabled, failureThreshold, baseWindow, maxWindow, maxPercent,
            MAX_INSTANCES_PER_SERVICE, metrics, clock);
    }

    /** Test seam: the same policy with a smaller instance cap. */
    InstanceEjection(boolean enabled, int failureThreshold, Duration baseWindow,
                     Duration maxWindow, int maxPercent, int maxInstancesPerService,
                     Metrics metrics, LongSupplier clock) {
        this.enabled = enabled;
        this.failureThreshold = failureThreshold;
        this.baseWindowNanos = Objects.requireNonNull(baseWindow, "baseWindow").toNanos();
        this.maxWindowNanos = Objects.requireNonNull(maxWindow, "maxWindow").toNanos();
        this.maxPercent = maxPercent;
        this.maxInstancesPerService = maxInstancesPerService;
        this.metrics = metrics;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Reads the {@code freeway.cloud.rpc.ejection.*} keys; {@code rpc.resilience=off} wins. */
    static InstanceEjection from(SymbolSource symbols, Metrics metrics) {
        if (ResilienceModule.resilienceDisabled(symbols)) {
            return disabled();
        }
        Settings settings = settings(symbols);
        return new InstanceEjection(
            settings.enabled(), settings.threshold(),
            Duration.ofNanos(settings.baseNanos()), Duration.ofNanos(settings.maxNanos()),
            settings.percent(), metrics, System::nanoTime);
    }

    /** Parses and validates the keys without building state (startup check). */
    static void validate(SymbolSource symbols) {
        if (!ResilienceModule.resilienceDisabled(symbols)) {
            settings(symbols);
        }
    }

    /** Library default: enabled, threshold 5, 30s doubling to 300s, 50% cap. */
    static InstanceEjection defaults(Metrics metrics) {
        return new InstanceEjection(
            true,
            ResilienceModule.ConfigKeys.RPC_EJECTION_FAILURE_THRESHOLD_DEFAULT,
            Duration.ofSeconds(ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW_DEFAULT),
            Duration.ofSeconds(ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_WINDOW_DEFAULT),
            ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_PERCENT_DEFAULT,
            metrics, System::nanoTime);
    }

    private static InstanceEjection disabled() {
        return new InstanceEjection(false, 1, Duration.ofSeconds(1), Duration.ofSeconds(1),
            100, null, System::nanoTime);
    }

    /**
     * The configured answer, validated eagerly so a typo fails startup rather
     * than the first call: the threshold and the percentage must be positive
     * (percentage ≤ 100), the windows positive with {@code max ≥ base}.
     */
    private static Settings settings(SymbolSource symbols) {
        boolean enabled = symbols.resolve(ENABLED);
        int threshold = symbols.resolve(FAILURE_THRESHOLD);
        long baseSeconds = symbols.resolve(BASE_WINDOW);
        long maxSeconds = symbols.resolve(MAX_WINDOW);
        int percent = symbols.resolve(MAX_PERCENT);
        if (threshold < 1) {
            throw new IllegalArgumentException(
                ResilienceModule.ConfigKeys.RPC_EJECTION_FAILURE_THRESHOLD
                    + " must be at least 1: " + threshold);
        }
        if (baseSeconds < 1) {
            throw new IllegalArgumentException(
                ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW
                    + " must be at least 1s: " + baseSeconds);
        }
        if (maxSeconds < baseSeconds) {
            throw new IllegalArgumentException(
                ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_WINDOW
                    + " must not be shorter than " + ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW
                    + ": max=" + maxSeconds + "s base=" + baseSeconds + "s");
        }
        if (percent < 1 || percent > 100) {
            throw new IllegalArgumentException(
                ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_PERCENT
                    + " must be between 1 and 100: " + percent);
        }
        return new Settings(
            enabled, threshold,
            secondsToNanos(ResilienceModule.ConfigKeys.RPC_EJECTION_BASE_WINDOW, baseSeconds),
            secondsToNanos(ResilienceModule.ConfigKeys.RPC_EJECTION_MAX_WINDOW, maxSeconds),
            percent);
    }

    /** {@link Duration#toNanos()} overflows past ~292 years; name the key instead. */
    private static long secondsToNanos(String key, long seconds) {
        try {
            return Duration.ofSeconds(seconds).toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(key + " is too large: " + seconds + "s", overflow);
        }
    }

    /**
     * The candidates a caller may choose from: the answer minus ejected members,
     * or the full answer when that would leave nothing (a partial topology must
     * never become an outage). The result is memoized: a hit costs one identity
     * check, one version check and one deadline check, with no allocation and no
     * per-instance lookup. A miss (new discovery snapshot, an ejection or
     * recovery, or an expired window) recomputes once and republishes the view.
     */
    List<ServiceInstance> filter(String serviceId, List<ServiceInstance> instances) {
        if (!enabled || instances.isEmpty()) {
            return instances;
        }
        ServiceEjection service = service(serviceId);
        long now = clock.getAsLong();
        long version = service.version.get();
        View cached = service.view;
        if (cached != null && cached.answer() == instances
                && cached.version() == version && now < cached.validUntil()) {
            return cached.filtered();
        }

        List<ServiceInstance> candidates = new ArrayList<>(instances.size());
        long earliestExpiry = Long.MAX_VALUE;
        for (ServiceInstance instance : instances) {
            InstanceState state = service.byInstance.get(instance.instanceId());
            if (state == null || !state.isEjected(now)) {
                candidates.add(instance);
            } else if (state.ejectedUntil < earliestExpiry) {
                // The earliest window expiry is the first moment this view can
                // change without a state write; until then it stays valid.
                earliestExpiry = state.ejectedUntil;
            }
        }
        List<ServiceInstance> filtered = candidates.isEmpty()
            ? instances
            : List.copyOf(candidates);
        service.view = new View(instances, version, earliestExpiry, filtered);
        return filtered;
    }

    /** A success clears the failure streak and any ejection (recovery is a successful call). */
    void onSuccess(String serviceId, ServiceInstance instance) {
        if (!enabled) {
            return;
        }
        ServiceEjection service = services.get(serviceId);
        if (service == null) {
            return;
        }
        InstanceState state = service.byInstance.get(instance.instanceId());
        if (state == null) {
            return;
        }
        boolean recovered = false;
        synchronized (state) {
            state.consecutiveFailures = 0;
            state.ejectedUntil = 0;
            if (state.ejectionCount > 0) {
                state.ejectionCount = 0;
                service.version.incrementAndGet();   // the cached view now excludes one less
                recovered = true;
            }
            state.lastTouched = clock.getAsLong();
        }
        if (recovered) {
            LOG.debug("Instance '{}' of service '{}' recovered — back in rotation",
                instance.instanceId(), serviceId);
            if (metrics != null) {
                // Telemetry must never change a call's outcome: a backend that
                // throws on counter creation (series budget, custom registry)
                // is a debug log, not a failed RPC.
                try {
                    metrics.counter("cloud.rpc.ejection.recovered", Map.of("service", serviceId))
                        .increment();
                } catch (RuntimeException thrown) {
                    LOG.debug("Ejection counter 'cloud.rpc.ejection.recovered' failed"
                        + " for service '{}'", serviceId, thrown);
                }
            }
        }
    }

    /**
     * One qualifying failure: count it, and eject at the threshold unless the
     * percentage guard refuses. A failure on an already-ejected instance
     * (possible only through the all-ejected fallback) does not extend the
     * window — the deadline is the policy.
     */
    void onFailure(String serviceId, ServiceInstance instance, RpcException failure) {
        if (!enabled || !qualifies(failure)) {
            return;
        }
        ServiceEjection service = service(serviceId);
        long now = clock.getAsLong();
        // Keep the map bounded even when no ejection ever fires (below-threshold
        // churn). Pruning before insertion cannot evict the state this call is
        // about to update.
        if (service.byInstance.size() > maxInstancesPerService) {
            prune(service, now);
        }
        InstanceState state = service.byInstance.computeIfAbsent(instance.instanceId(), id -> {
            InstanceState created = new InstanceState();
            created.lastTouched = now;
            return created;
        });
        boolean ejected = false;
        long ejectedForNanos = 0;
        int ejectionNumber = 0;
        // One monitor per service makes the percentage check and the ejection
        // write atomic across instances (a per-instance monitor let two
        // concurrent threshold crossings both pass the cap). The per-instance
        // monitor still guards its own streak.
        synchronized (service) {
            synchronized (state) {
                if (state.isEjected(now)) {
                    return;
                }
                state.lastTouched = now;
                if (++state.consecutiveFailures < failureThreshold) {
                    return;
                }
                List<ServiceInstance> answer = service.answer();
                int ejectedNow = service.ejectedCount(now);
                if (answer.isEmpty()
                        || (long) (ejectedNow + 1) * 100 > (long) maxPercent * answer.size()) {
                    // Keep the streak at the threshold: the next failure re-checks,
                    // and the instance is not taken out while the cap forbids it.
                    state.consecutiveFailures = failureThreshold;
                    return;
                }
                ejectedForNanos = windowNanos(state.ejectionCount);
                state.ejectedUntil = now + ejectedForNanos;
                state.ejectionCount++;
                ejectionNumber = state.ejectionCount;
                state.consecutiveFailures = 0;
                service.version.incrementAndGet();       // the cached view is stale now
                ejected = true;
            }
        }
        if (ejected) {
            LOG.debug("Ejecting instance '{}' of service '{}' for {}ms (ejection #{}): {}",
                instance.instanceId(), serviceId, ejectedForNanos / 1_000_000,
                ejectionNumber, failure.getMessage());
            if (metrics != null) {
                try {
                    metrics.counter("cloud.rpc.ejection.ejected", Map.of("service", serviceId))
                        .increment();
                } catch (RuntimeException thrown) {
                    LOG.debug("Ejection counter 'cloud.rpc.ejection.ejected' failed"
                        + " for service '{}'", serviceId, thrown);
                }
            }
            prune(service, now);
        }
    }

    /** Whether the failure is the chosen instance's own wire failure. */
    static boolean qualifies(RpcException failure) {
        return switch (failure.kind()) {
            case CONNECT, TRANSPORT -> true;
            // No cause = the caller's budget was the effective bound (spent
            // before the send, or the send timed out inside a narrowed caller
            // deadline), so it is not the instance's fault.
            case TIMEOUT -> failure.getCause() != null;
            case HTTP -> failure.status() >= 500;
            default -> false;
        };
    }

    /** Number of tracked instances for a service (bounded-state seam for tests). */
    int trackedInstances(String serviceId) {
        ServiceEjection service = services.get(serviceId);
        return service == null ? 0 : service.byInstance.size();
    }

    private long windowNanos(int priorEjections) {
        long window = baseWindowNanos;
        for (int i = 0; i < priorEjections && window < maxWindowNanos; i++) {
            window = window >= maxWindowNanos / 2 ? maxWindowNanos : window * 2;
        }
        return Math.min(window, maxWindowNanos);
    }

    private ServiceEjection service(String serviceId) {
        ServiceEjection existing = services.get(serviceId);
        if (existing != null) {
            return existing;
        }
        if (services.size() >= MAX_SERVICES) {
            Iterator<String> it = services.keySet().iterator();
            if (it.hasNext()) {
                services.remove(it.next());
            }
        }
        return services.computeIfAbsent(serviceId, id -> new ServiceEjection());
    }

    /** Keeps one service's map bounded: idle non-ejected entries go first, then the oldest idle. */
    private void prune(ServiceEjection service, long now) {
        if (service.byInstance.size() <= maxInstancesPerService) {
            return;
        }
        long idleNanos = maxWindowNanos > Long.MAX_VALUE / IDLE_WINDOWS
            ? Long.MAX_VALUE
            : maxWindowNanos * IDLE_WINDOWS;
        Iterator<Map.Entry<String, InstanceState>> entries = service.byInstance.entrySet().iterator();
        while (entries.hasNext()) {
            InstanceState state = entries.next().getValue();
            if (!state.isEjected(now) && now - state.lastTouched > idleNanos) {
                entries.remove();
            }
        }
        while (service.byInstance.size() > maxInstancesPerService) {
            String victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, InstanceState> entry : service.byInstance.entrySet()) {
                InstanceState state = entry.getValue();
                if (!state.isEjected(now) && state.lastTouched < oldest) {
                    oldest = state.lastTouched;
                    victim = entry.getKey();
                }
            }
            if (victim == null) {
                return; // everything is ejected: the cap waits for the windows
            }
            service.byInstance.remove(victim);
        }
    }

    /** The four validated settings, shared by {@link #from} and {@link #validate}. */
    private record Settings(boolean enabled, int threshold, long baseNanos, long maxNanos,
                            int percent) {}

    /**
     * A memoized filtered view. Valid while the discovery answer is the same
     * snapshot, the state version is unchanged and no ejection window listed
     * here has expired — the only three ways the filtered set can change.
     */
    private record View(List<ServiceInstance> answer, long version, long validUntil,
                        List<ServiceInstance> filtered) {}

    /** One service's last discovery answer plus its per-instance state. */
    private static final class ServiceEjection {
        private final ConcurrentHashMap<String, InstanceState> byInstance = new ConcurrentHashMap<>();
        /** Bumps on every ejection and recovery — the writes that alter filtering. */
        private final AtomicLong version = new AtomicLong();
        /** Most recent filtered view; immutable, safe to hand to any caller. */
        private volatile View view;

        List<ServiceInstance> answer() {
            View current = view;
            return current == null ? List.of() : current.answer();
        }

        int ejectedCount(long now) {
            int ejected = 0;
            for (ServiceInstance instance : answer()) {
                InstanceState state = byInstance.get(instance.instanceId());
                if (state != null && state.isEjected(now)) {
                    ejected++;
                }
            }
            return ejected;
        }
    }

    /** Per-instance state; the volatile deadline is read lock-free by the filter. */
    private static final class InstanceState {
        private volatile long ejectedUntil;   // nanoTime deadline, 0 = not ejected
        private int consecutiveFailures;      // guarded by this
        private int ejectionCount;            // guarded by this
        private volatile long lastTouched;

        boolean isEjected(long now) {
            return ejectedUntil != 0 && ejectedUntil - now > 0;
        }
    }
}
