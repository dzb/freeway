package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test-only mutable discovery: cloud tests register the app's own provider
 * endpoints without depending on freeway-fleet. Production code uses
 * LocalDiscoveryModule/FleetModule/StaticDiscoveryModule instead.
 */
public final class TestDiscoveryModule implements ModuleEx {

    @Override
    public void bind(Binder b) {
        Store store = new Store();
        b.bind(Store.class).to(container -> store);
        b.bind(ServiceRegistry.class).to(container -> store.registry()).primary();
        b.bind(ServiceDiscovery.class).to(container -> store.discovery()).primary();
    }

    private static final class Store {
        private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();

        ServiceRegistry registry() {
            return new ServiceRegistry() {
                @Override
                public void register(ServiceInstance instance) {
                    instances.put(instance.instanceId(), instance);
                }

                @Override
                public boolean renew(String serviceId, String instanceId) {
                    return instances.containsKey(instanceId);
                }

                @Override
                public void unregister(ServiceInstance instance) {
                    instances.remove(instance.instanceId());
                }
            };
        }

        ServiceDiscovery discovery() {
            return serviceId -> instances.values().stream()
                .filter(i -> i.serviceId().equals(serviceId))
                .toList();
        }
    }
}
