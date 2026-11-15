package com.jujin.freeway.fleet;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.util.List;
import java.util.Objects;

/**
 * The fleet as a {@link ServiceDiscovery}: the union of what the alive nodes
 * answer, deduplicated by instance id (every node holds a full copy, so the
 * same instance arrives once per node).
 */
final class FleetDiscovery implements ServiceDiscovery {

    private final FleetClient client;

    FleetDiscovery(FleetClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        return client.instances(serviceId);
    }
}
