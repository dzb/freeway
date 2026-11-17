package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void configMapsServiceIdsToEndpointsAndRepeatedInstances() {
        var discovery = StaticDiscovery.of(symbols(
            "agent=http://10.0.0.1:8080,"
                + "agent=https://agent.internal:443/bff,"
                + "billing=http://billing.default.svc:8080"));

        List<ServiceInstance> agents = discovery.instances("agent");
        assertEquals(2, agents.size());
        assertEquals("http://10.0.0.1:8080", agents.get(0).instanceId());
        assertEquals("https://agent.internal:443/bff", agents.get(1).instanceId());
        assertEquals("http://billing.default.svc:8080",
            discovery.instances("billing").get(0).endpoint().uri().toString());
    }

    @Test
    void configRequiresAtLeastOneEntry() {
        assertThrows(IllegalArgumentException.class,
            () -> StaticDiscovery.of(symbols(null)));
        assertThrows(IllegalArgumentException.class,
            () -> StaticDiscovery.of(symbols(" , ,")));
    }

    @Test
    void configRejectsMalformedAndDuplicateEntries() {
        assertThrows(IllegalArgumentException.class,
            () -> StaticDiscovery.of(symbols("agent")));
        assertThrows(IllegalArgumentException.class,
            () -> StaticDiscovery.of(symbols("agent=http://host")));
        assertThrows(IllegalArgumentException.class,
            () -> StaticDiscovery.of(symbols(
                "agent=http://host:8080,agent=http://host:8080")));
    }

    private static SymbolSource symbols(String raw) {
        Map<String, String> values = raw == null
            ? Map.of()
            : Map.of(StaticModule.ConfigKeys.SERVICES, raw);
        return SymbolSource.of(
            new CoercerDefault(),
            SymbolProvider.of(() -> values, 0));
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
