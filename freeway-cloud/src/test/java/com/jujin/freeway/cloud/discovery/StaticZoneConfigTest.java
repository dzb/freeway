package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The default balancer reads the zone from the shared discovery policy key. */
class StaticZoneConfigTest {

    @AfterEach
    void clear() {
        System.clearProperty(LoadBalancer.ConfigKeys.LOCAL_ZONE);
    }

    @Test
    void configuredLocalZoneIsReadByTheDefaultBalancer() {
        System.setProperty(LoadBalancer.ConfigKeys.LOCAL_ZONE, "zone-a");
        var local = ServiceInstance.of("svc", "local",
            ServiceEndpoint.of("http", "127.0.0.1", 8080), Map.of("zone", "zone-a"));
        var remote = ServiceInstance.of("svc", "remote",
            ServiceEndpoint.of("http", "127.0.0.1", 8080), Map.of("zone", "zone-b"));
        try (Container container = Freeway.create(
                StaticModule.of("svc", local, remote))) {
            LoadBalancer balancer = container.get(LoadBalancer.class);
            assertEquals("local", balancer.choose(List.of(remote, local)).orElseThrow().instanceId());
        }
    }

    @Test
    void aCustomBalancerMarksPrimaryWithoutAmbiguity() {
        var one = ServiceInstance.of("svc", "one",
            ServiceEndpoint.of("http", "127.0.0.1", 8080));
        var two = ServiceInstance.of("svc", "two",
            ServiceEndpoint.of("http", "127.0.0.1", 8081));
        LoadBalancer custom = instances -> instances.stream().reduce((a, b) -> b);
        try (Container container = Freeway.create(
                StaticModule.of("svc", one, two),
                binder -> binder.bind(LoadBalancer.class)
                    .to(c -> custom)
                    .primary())) {
            assertEquals("two", container.get(LoadBalancer.class)
                .choose(List.of(one, two)).orElseThrow().instanceId(),
                "the caller's primary balancer must win over StaticModule's fallback");
        }
    }

    @Test
    void staticModuleDeclaresTheBalancerPolicyKey() {
        try (Container container = Freeway.create(
                StaticModule.of("svc", ServiceInstance.of("svc", "one",
                    ServiceEndpoint.of("http", "127.0.0.1", 8080))))) {
            assertTrue(container.extension(KnownKeys.class).all().stream()
                    .anyMatch(keys -> keys.keys().contains(LoadBalancer.ConfigKeys.LOCAL_ZONE)),
                "the module that reads local-zone must declare it");
        }
    }

}
