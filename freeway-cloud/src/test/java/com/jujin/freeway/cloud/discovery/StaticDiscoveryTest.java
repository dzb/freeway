package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaticDiscoveryTest {

    private static ServiceInstance instance(String id, String host) {
        return ServiceInstance.of("agent", id, ServiceEndpoint.of("http", host, 8080), Map.of());
    }

    @Test
    void aListServesMultipleInstancesForClientSideBalancing() {
        var a = instance("a", "10.0.0.1");
        var b = instance("b", "10.0.0.2");
        var discovery = StaticDiscovery.of(Map.of("agent", List.of(a, b)));

        assertEquals(List.of(a, b), discovery.instances("agent"));
        assertTrue(discovery.instances("unknown").isEmpty());
    }

    @Test
    void aSingleEndpointIsTheVipForm() {
        var vip = instance("agent-vip", "agent.internal");
        var discovery = StaticDiscovery.of("agent", vip);

        assertEquals(List.of(vip), discovery.instances("agent"));
    }

    @Test
    void mapsAreDefensivelyCopied() {
        var a = instance("a", "10.0.0.1");
        var discovery = StaticDiscovery.of("agent", a);

        assertEquals(List.of(a), discovery.instances("agent"));
        assertThrows(UnsupportedOperationException.class,
            () -> discovery.instances("agent").add(a));
    }

    @Test
    void mixedListAndVipServicesAreSupportedInOneMap() {
        var a = instance("a", "10.0.0.1");
        var vip = instance("vip", "agent-vip");
        var discovery = StaticDiscovery.of(Map.of(
            "agent", List.of(a),
            "bff", List.of(vip)));

        assertEquals(List.of(a), discovery.instances("agent"));
        assertEquals(List.of(vip), discovery.instances("bff"));
    }
}
