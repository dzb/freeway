package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.ioc.Freeway;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
}
