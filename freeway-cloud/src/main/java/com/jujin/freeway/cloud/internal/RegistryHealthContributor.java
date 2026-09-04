package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.health.HealthResult;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;

/**
 * Built-in readiness contributor for the registry, contributed by
 * {@code CloudDiscoveryModule}. The local in-process store has no external
 * connectivity to probe, so the check is always healthy and reports the
 * registered instance count as detail. External-backend connectivity is
 * contributed by a custom registry adapter bound primary (freeway-ext
 * ships no cloud adapters yet). When an external adapter is bound primary, the local
 * discovery/registry implementations are no longer selected, so this
 * contributor deactivates itself and leaves readiness to the adapter's own
 * {@link CloudHealthContributor}.
 */
public final class RegistryHealthContributor implements CloudHealthContributor {

    private final RegistryStore store;
    private final boolean active;

    public RegistryHealthContributor(RegistryStore store, ActiveBindingProbe probe) {
        this.store = store;
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
        return new HealthResult(true, "instances=" + store.instanceCount());
    }
}
