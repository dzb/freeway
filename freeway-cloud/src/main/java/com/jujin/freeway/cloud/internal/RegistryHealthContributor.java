package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.health.CloudHealthContributor;
import com.jujin.freeway.cloud.health.HealthResult;

/**
 * Built-in readiness contributor for the registry, contributed by
 * {@code CloudDiscoveryModule}. The local in-process store has no external
 * connectivity to probe, so the check is always healthy and reports the
 * registered instance count as detail. External-backend connectivity
 * (Nacos/Consul/K8s) arrives with the matching freeway-ext adapter as a
 * separate contributor.
 */
public final class RegistryHealthContributor implements CloudHealthContributor {

    private final RegistryStore store;

    public RegistryHealthContributor(RegistryStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "registry";
    }

    @Override
    public HealthResult check() {
        return new HealthResult(true, "instances=" + store.instanceCount());
    }
}
