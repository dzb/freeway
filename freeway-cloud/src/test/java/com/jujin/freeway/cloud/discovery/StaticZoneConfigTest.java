package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
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
}
