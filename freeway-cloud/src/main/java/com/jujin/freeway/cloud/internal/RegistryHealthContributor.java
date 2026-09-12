package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.health.HealthResult;

/**
 * Readiness for the registry: reports what {@link RegistryRenewal} observed
 * rather than assuming registration worked.
 *
 * <p>Active whenever the built-in registry is in use. The verdict is
 * per-instance state, decided in {@link #check()} rather than at construction:
 * this contributor is built before the registry hook runs, so it cannot know
 * yet whether this boot registers anything. Unhealthy only after
 * {@link RegistryRenewal#UNHEALTHY_AFTER} consecutive failed heartbeats, so a
 * single blip does not pull the pod out of rotation; an instance that never
 * registered reports healthy with {@code instances=0} instead of a vacuous
 * all-clear.</p>
 *
 * <p>External-backend connectivity is contributed by a custom registry
 * adapter bound primary instead (freeway-ext ships no cloud adapters yet).
 * Once either discovery or registry runs on an external adapter, that role's
 * local implementation is no longer selected, so this contributor deactivates
 * itself and leaves readiness to the adapter's own
 * {@link CloudHealthContributor}.</p>
 */
public final class RegistryHealthContributor implements CloudHealthContributor {

    private final RegistryStore store;
    private final RegistryRenewal renewal;
    private final boolean active;

    public RegistryHealthContributor(RegistryStore store, ActiveBindingProbe probe,
                                     RegistryRenewal renewal) {
        this.store = store;
        this.renewal = renewal;
        this.active = probe.isLocal(ServiceDiscovery.class)
            && probe.isLocal(ServiceRegistry.class);
    }

    @Override
    public String name() {
        return "registry";
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public HealthResult check() {
        if (renewal.isDraining()) {
            // Shutdown has begun: answer 503 so a probe-driven load balancer
            // stops routing here while the drain window runs.
            return HealthResult.unhealthy("draining");
        }
        if (!renewal.isTracking()) {
            return new HealthResult(true, "not registered");
        }
        if (!renewal.isHealthy()) {
            return HealthResult.unhealthy("heartbeat failed " + renewal.consecutiveFailures()
                + " times in a row");
        }
        return new HealthResult(true, "instances=" + store.instanceCount());
    }
}
