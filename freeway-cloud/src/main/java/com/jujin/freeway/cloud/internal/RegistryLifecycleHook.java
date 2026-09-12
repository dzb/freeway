package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.RuntimeHook;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry lifecycle hook: runs AFTER {@code freeway.http.server} so
 * {@code host:port} are known. Collects every {@link ServiceDeclaration}
 * contribution, registers each instance and starts the renew heartbeat; on
 * stop, halts the heartbeat and unregisters everything (traffic is removed
 * before the HTTP server shuts down — hooks stop in reverse order).
 *
 * <p>Every heartbeat also verifies that the registry still lists the instance
 * and re-registers it when it does not: an entry lost to eviction (a long
 * pause, a registry restart, an adapter that dropped its lease) would otherwise
 * leave the process running and invisible until someone restarted it. Outcomes
 * are published to {@link RegistryRenewal}, which {@code /health/ready} reads —
 * so readiness reflects the registration instead of assuming it.</p>
 */
public final class RegistryLifecycleHook implements RuntimeHook {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryLifecycleHook.class);
    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(10);
    /** Deployment drain window; the default comes from the shared key source. */
    private static final SymbolSpec<Duration> SHUTDOWN_DRAIN = SymbolSpec.of(
        CloudConfigKeys.REGISTRY_SHUTDOWN_DRAIN, Duration.class,
        CloudConfigKeys.REGISTRY_SHUTDOWN_DRAIN_DEFAULT);

    private final RegistryRenewal renewal;
    private final Duration renewInterval;
    /** Time to keep serving after deregistering (see the config key). */
    private volatile Duration shutdownDrain = Duration.ZERO;
    private final List<ServiceInstance> registered = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile ServiceRegistry registryRef;

    public RegistryLifecycleHook(RegistryRenewal renewal) {
        this(renewal, RENEW_INTERVAL);
    }

    /** Test seam: a shorter cadence than the production 10 seconds. */
    RegistryLifecycleHook(RegistryRenewal renewal, Duration renewInterval) {
        this.renewal = Objects.requireNonNull(renewal, "renewal");
        this.renewInterval = Objects.requireNonNull(renewInterval, "renewInterval");
    }

    @Override
    public void start(Container container) throws Exception {
        ServiceRegistry registry = container.get(ServiceRegistry.class);
        registryRef = registry;
        shutdownDrain = container.get(SymbolSource.class).resolve(SHUTDOWN_DRAIN);
        for (ServiceDeclaration declaration : container.extension(ServiceDeclaration.class).all()) {
            ServiceInstance instance = declaration.resolve(container);
            if (instance == null) {
                continue; // declaration not applicable this boot
            }
            registry.register(instance);
            registered.add(instance);
            LOG.info("Registered service '{}' instance '{}' at {}", instance.serviceId(),
                instance.instanceId(), instance.endpoint());
        }
        if (!registered.isEmpty()) {
            renewal.track();
                long intervalMillis = renewInterval.toMillis();
            scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("cloud-registry-heartbeat").factory());
            scheduler.scheduleWithFixedDelay(this::heartbeat, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * One heartbeat: renew, and re-register whatever the registry no longer
     * holds. The registry's own answer is the verification — asking discovery
     * afterwards would be a second, weaker opinion, and for an adapter
     * backend a second network round trip on every interval.
     */
    private void heartbeat() {
        ServiceRegistry registry = registryRef;
        if (registry == null) {
            return;
        }
        boolean healthy = true;
        for (ServiceInstance instance : registered) {
            boolean stillHeld;
            try {
                stillHeld = registry.renew(instance.serviceId(), instance.instanceId());
            } catch (Exception ex) {
                LOG.warn("Heartbeat renew failed for {} instance {}: {}",
                    instance.serviceId(), instance.instanceId(), ex.getMessage());
                healthy = false;
                continue;
            }
            if (stillHeld) {
                continue;
            }
            healthy = false;
            try {
                registry.register(instance);
                LOG.warn("Registration for {} instance {} was gone — re-registered",
                    instance.serviceId(), instance.instanceId());
            } catch (Exception ex) {
                LOG.warn("Re-registration failed for {} instance {}: {}",
                    instance.serviceId(), instance.instanceId(), ex.getMessage());
            }
        }
        if (healthy) {
            renewal.renewed();
        } else {
            renewal.failed();
        }
    }

    /**
     * Keeps the process serving after the signals went out. Requests already
     * routed here finish instead of hitting a closed socket — the difference
     * between a rolling update that drops traffic and one that does not.
     */
    private void drain(long drainMillis) {
        if (drainMillis <= 0) {
            return;
        }
        LOG.info("Draining for {} ms before shutdown", drainMillis);
        try {
            Thread.sleep(drainMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Drain interrupted after the signals were sent");
        }
    }


    @Override
    public void stop(Container container) {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
        }
        // Signal first, then wait: readiness turns unhealthy and the entry
        // leaves the registry at the same moment, so a probe-driven and a
        // registry-driven load balancer both get the whole window to react.
        if (!registered.isEmpty()) {
            renewal.draining();
        }
        ServiceRegistry registry;
        try {
            registry = container.get(ServiceRegistry.class);
        } catch (Exception ex) {
            // The container is already dismantling around us: a missing
            // registry is not worth failing the shutdown over — the lease
            // expires on its own.
            LOG.warn("Deregistration skipped: {}", ex.toString());
            registered.clear();
            return;
        }
        for (ServiceInstance instance : registered) {
            try {
                registry.unregister(instance);
                LOG.info("Deregistered service '{}' instance '{}'", instance.serviceId(), instance.instanceId());
            } catch (Exception ex) {
                LOG.warn("Deregister failed for {} instance {}: {}",
                    instance.serviceId(), instance.instanceId(), ex.getMessage());
            }
        }
        registered.clear();
        drain(shutdownDrain.toMillis());
    }
}
