package com.jujin.freeway.fleet;

import com.jujin.freeway.cloud.internal.ActiveBindingProbe;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.http.HealthResult;

/**
 * Readiness for the registry: reports what {@link FleetRenewal} observed
 * rather than assuming registration worked.
 *
 * <p>What it reports — {@code draining}, heartbeat failures, registration — is
 * this module's own state, updated by {@code FleetLifecycleHook} whichever
 * {@code ServiceRegistry} is bound, local or external (the Consul adapter
 * included). That is why the check does not depend on the backend being @Local:
 * readiness has to keep answering "not registered / draining / heartbeat
 * failing" no matter who backs the registry. An adapter may add a connectivity
 * check of its own next to this one; it does not stand in for it.</p>
 *
 * <p>The verdict is per-instance state, decided in {@link #check()} rather than
 * at construction: this contributor is built before the registry hook runs, so
 * it cannot know yet whether this boot registers anything. Unhealthy only after
 * {@link FleetRenewal#UNHEALTHY_AFTER} consecutive failed heartbeats, so a
 * single blip does not pull the pod out of rotation; an instance that never
 * registered reports healthy with {@code instances=0} instead of a vacuous
 * all-clear (the count is only meaningful for the built-in store, so an external
 * backend reports {@code backend=external} instead).</p>
 */
public final class FleetHealthContributor implements CloudHealthContributor {

    private final FleetStore store;
    private final FleetRenewal renewal;
    private final boolean localStore;

    public FleetHealthContributor(FleetStore store, ActiveBindingProbe probe,
                                     FleetRenewal renewal) {
        this.store = store;
        this.renewal = renewal;
        this.localStore = probe.isLocal(ServiceRegistry.class);
    }

    @Override
    public String name() {
        return "registry";
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
        return new HealthResult(true, localStore
            ? "instances=" + store.instanceCount()
            : "backend=external");
    }
}
