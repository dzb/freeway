package com.jujin.freeway.cloud.registry;

import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.util.List;
import java.util.Objects;

/**
 * The fleet as a {@link ServiceDiscovery}: the union of what the alive nodes
 * answer, deduplicated by instance id (every node holds a full copy, so the
 * same instance arrives once per node).
 */
final class RegistryNodeServiceDiscovery implements ServiceDiscovery {

    private final RegistryNodeClient client;

    RegistryNodeServiceDiscovery(RegistryNodeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public List<ServiceInstance> instances(String serviceId) {
        return client.instances(serviceId);
    }
}
