package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.cloud.internal.RegistryStore;

import java.util.Objects;

/**
 * In-process {@link ServiceRegistry} backed by {@link RegistryStore}.
 * Production-usable for single-process / static-topology deployments.
 * Cross-process registration/discovery needs a custom backend bound primary
 * (an extension module — freeway-ext ships no cloud adapters yet).
 */
public final class ServiceRegistryDefault implements ServiceRegistry {

    private final RegistryStore store;

    public ServiceRegistryDefault(RegistryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void register(ServiceInstance instance) {
        store.register(instance);
    }

    @Override
    public void renew(String serviceId, String instanceId) {
        store.renew(serviceId, instanceId);
    }

    @Override
    public void unregister(ServiceInstance instance) {
        store.unregister(instance);
    }
}
