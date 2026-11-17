package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticModuleTest {

    private static ServiceInstance instance(String id, String host) {
        return ServiceInstance.of("agent", id, ServiceEndpoint.of("http", host, 8080), Map.of());
    }

    @Test
    void bindsDiscoveryAndTheClientSideLoadBalancerWithoutARegistry() {
        var a = instance("a", "10.0.0.1");
        var b = instance("b", "10.0.0.2");
        try (var container = Freeway.create(
                StaticModule.of(Map.of("agent", List.of(a, b))))) {
            var discovery = container.get(ServiceDiscovery.class);
            var balancer = container.get(LoadBalancer.class);

            assertEquals(List.of(a, b), discovery.instances("agent"));
            assertTrue(balancer.choose(discovery.instances("agent")).isPresent());
        }
    }

    @Test
    void aSingleVipEndpointIsStillAOneElementList() {
        var vip = instance("vip", "agent.internal");
        try (var container = Freeway.create(StaticModule.of("agent", vip))) {
            assertEquals(List.of(vip), container.get(ServiceDiscovery.class).instances("agent"));
        }
    }

    @Test
    void configDrivenModuleReadsEndpointsAndDeclaresItsKey() {
        System.setProperty(StaticModule.ConfigKeys.SERVICES,
            "agent=http://10.0.0.1:8080,agent=http://10.0.0.2:8081");
        try (var container = Freeway.create(new StaticModule())) {
            var discovery = container.get(ServiceDiscovery.class);
            assertEquals(2, discovery.instances("agent").size());
            assertEquals("http://10.0.0.1:8080",
                discovery.instances("agent").get(0).instanceId());
            assertTrue(container.extension(KnownKeys.class).all().stream()
                .anyMatch(keys -> keys.keys().contains(StaticModule.ConfigKeys.SERVICES)));
            assertThrows(IllegalArgumentException.class,
                () -> container.get(ServiceRegistry.class));
        }
    }

    @Test
    void configDrivenModuleFailsWhenNoServicesAreConfigured() {
        try (var container = Freeway.create(new StaticModule())) {
            assertThrows(IllegalArgumentException.class,
                () -> container.get(ServiceDiscovery.class).instances("agent"));
        }
    }

    @Test
    void configValidationHookFailsAtStartup() throws Exception {
        try (var container = Freeway.create(new StaticModule())) {
            RuntimeHook hook = container.extension(RuntimeHook.class).all().getFirst();
            assertThrows(IllegalArgumentException.class, () -> hook.start(container));
        }
    }

    @AfterEach
    void clearServices() {
        System.clearProperty(StaticModule.ConfigKeys.SERVICES);
    }
}
