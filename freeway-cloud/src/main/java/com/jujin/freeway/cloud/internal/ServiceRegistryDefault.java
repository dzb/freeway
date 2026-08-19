package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;

import java.util.Objects;

/**
 * In-process {@link ServiceRegistry} backed by {@link RegistryStore}.
 * Production-usable for single-process / static-topology deployments;
 * cross-process dynamic discovery is provided by freeway-ext backends.
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
    public void deregister(ServiceInstance instance) {
        store.deregister(instance);
    }
}
