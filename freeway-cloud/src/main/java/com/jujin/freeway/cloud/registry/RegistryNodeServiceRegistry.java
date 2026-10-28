package com.jujin.freeway.cloud.registry;

import com.jujin.freeway.cloud.discovery.Health;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import java.util.Objects;

/**
 * The fleet as a {@link ServiceRegistry}: every write fans out to all nodes.
 * The lifecycle around these calls — register on start, heartbeat in between,
 * unregister on stop, re-register on a lost lease — is the discovery module's
 * {@code RegistryLifecycleHook}, driven through this interface, never copied.
 */
final class RegistryNodeServiceRegistry implements ServiceRegistry {

    private final RegistryNodeClient client;

    RegistryNodeServiceRegistry(RegistryNodeClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public void register(ServiceInstance instance) {
        client.register(instance);
    }

    @Override
    public void register(ServiceInstance instance, Health health) {
        client.register(instance, health);
    }

    @Override
    public boolean renew(String serviceId, String instanceId) {
        return client.renew(serviceId, instanceId);
    }

    @Override
    public void markReady(ServiceInstance instance, boolean ready) {
        client.markReady(instance, ready);
    }

    @Override
    public void unregister(ServiceInstance instance) {
        client.unregister(instance);
    }
}
