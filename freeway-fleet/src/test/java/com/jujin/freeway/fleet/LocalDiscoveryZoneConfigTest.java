package com.jujin.freeway.fleet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.fleet.LocalDiscoveryModule.ConfigKeys;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.cloud.discovery.LoadBalancer;
import com.jujin.freeway.cloud.discovery.ServiceEndpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.ioc.Freeway;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The local zone is a configuration input to the default balancer, not a constructor-only test knob. */
class LocalDiscoveryZoneConfigTest {

    @AfterEach
    void clear() {
        System.clearProperty(ConfigKeys.DISCOVERY_LOCAL_ZONE);
    }

    @Test
    void configuredLocalZoneIsReadByTheDefaultBalancer() {
        System.setProperty(ConfigKeys.DISCOVERY_LOCAL_ZONE, "zone-a");
        try (Container container = Freeway.create(new LocalDiscoveryModule())) {
            LoadBalancer balancer = container.get(LoadBalancer.class);
            var local = ServiceInstance.of("svc", "local",
                ServiceEndpoint.of("http", "127.0.0.1", 8080), Map.of("zone", "zone-a"));
            var remote = ServiceInstance.of("svc", "remote",
                ServiceEndpoint.of("http", "127.0.0.1", 8080), Map.of("zone", "zone-b"));

            assertEquals("local", balancer.choose(List.of(remote, local)).orElseThrow().instanceId());
        }
    }
}
