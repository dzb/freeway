package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
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

    private final RegistryRenewal renewal;
    private final Duration renewInterval;
    private final List<ServiceInstance> registered = new CopyOnWriteArrayList<>();
    private volatile ScheduledExecutorService scheduler;
    private volatile ServiceRegistry registryRef;
    private volatile ServiceDiscovery discovery;

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
            discovery = container.get(ServiceDiscovery.class);
            long intervalMillis = renewInterval.toMillis();
            scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("cloud-registry-heartbeat").factory());
            scheduler.scheduleWithFixedDelay(this::heartbeat, intervalMillis, intervalMillis,
                TimeUnit.MILLISECONDS);
        }
    }

    /**
     * One heartbeat: renew, then verify. Verification uses the same discovery
     * query a consumer would run, so it sees what the registry actually
     * publishes — for an adapter backend, that is the backend's own view.
     */
    private void heartbeat() {
        ServiceRegistry registry = registryRef;
        if (registry == null) {
            return;
        }
        boolean healthy = true;
        for (ServiceInstance instance : registered) {
            try {
                registry.renew(instance.serviceId(), instance.instanceId());
            } catch (Exception ex) {
                LOG.warn("Heartbeat renew failed for {} instance {}: {}",
                    instance.serviceId(), instance.instanceId(), ex.getMessage());
                healthy = false;
            }
            if (!listed(instance)) {
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
        }
        if (healthy) {
            renewal.renewed();
        } else {
            renewal.failed();
        }
    }

    /** True when discovery currently lists this exact instance. */
    private boolean listed(ServiceInstance instance) {
        ServiceDiscovery source = discovery;
        if (source == null) {
            return true; // no discovery in this assembly: nothing to verify against
        }
        try {
            return source.getInstances(instance.serviceId()).stream()
                .anyMatch(candidate -> candidate.instanceId().equals(instance.instanceId()));
        } catch (Exception ex) {
            LOG.warn("Could not verify registration of {} instance {}: {}",
                instance.serviceId(), instance.instanceId(), ex.getMessage());
            return true; // a discovery read failure is not evidence of losing the entry
        }
    }

    @Override
    public void stop(Container container) {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
            scheduler = null;
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
    }
}
