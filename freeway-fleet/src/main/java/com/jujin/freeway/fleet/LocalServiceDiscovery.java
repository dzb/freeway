package com.jujin.freeway.fleet;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;

import java.util.List;
import java.util.Objects;

/**
 * In-process {@link ServiceDiscovery} backed by {@link FleetStore}.
 * Returns live && ready, non-stale instances; stale entries are evicted lazily.
 */
public final class LocalServiceDiscovery implements ServiceDiscovery {

    private final FleetStore store;

    public LocalServiceDiscovery(FleetStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        return store.liveReady(serviceId, FleetStore.DEFAULT_EVICTION);
    }
}
