package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.cloud.internal.RegistryStore;

import java.util.List;
import java.util.Objects;

/**
 * In-process {@link ServiceDiscovery} backed by {@link RegistryStore}.
 * Returns live && ready, non-stale instances; stale entries are evicted lazily.
 */
public final class ServiceDiscoveryDefault implements ServiceDiscovery {

    private final RegistryStore store;

    public ServiceDiscoveryDefault(RegistryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<ServiceInstance> getInstances(String serviceId) {
        return store.liveReady(serviceId, RegistryStore.DEFAULT_EVICTION);
    }
}
