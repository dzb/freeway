package com.jujin.freeway.fleet;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;

import java.util.List;
import java.util.Objects;

/**
 * In-process {@link ServiceDiscovery} backed by {@link LeaseStore}.
 * Returns live && ready, non-stale instances; stale entries are evicted lazily.
 */
final class LocalDiscovery implements ServiceDiscovery {

    private final LeaseStore store;

    LocalDiscovery(LeaseStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        return store.liveReadyInstances(serviceId, LeaseStore.DEFAULT_EVICTION);
    }
}
