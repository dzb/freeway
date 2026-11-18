package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.ioc.symbol.SymbolProvider;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsDiscoveryTest {

    private static final ServiceEndpoint ORDERS = ServiceEndpoint.of("http", "orders.svc", 8080);

    private static HostResolver answers(String host, String... literals) {
        return requested -> {
            if (!requested.equals(host)) {
                throw new UnknownHostException(requested);
            }
            return addresses(literals);
        };
    }

    private static InetAddress[] addresses(String... literals) {
        InetAddress[] resolved = new InetAddress[literals.length];
        for (int i = 0; i < literals.length; i++) {
            try {
                resolved[i] = InetAddress.getByName(literals[i]);
            } catch (UnknownHostException e) {
                throw new AssertionError(e); // literals never resolve, they parse
            }
        }
        return resolved;
    }

    private static DnsDiscovery discovery(HostResolver resolver, DnsDiscovery.AddressFamily family) {
        return new DnsDiscovery(Map.of("orders", ORDERS), resolver, family);
    }

    @Test
    void everyResolvedAddressBecomesOneInstanceWithALiteralEndpoint() {
        var discovery = discovery(answers("orders.svc", "10.0.0.5", "10.0.0.7"),
            DnsDiscovery.AddressFamily.ANY);

        List<ServiceInstance> instances = discovery.instances("orders");

        assertEquals(2, instances.size());
        assertEquals("http://10.0.0.5:8080", instances.get(0).endpoint().uri().toString());
        assertEquals("http://10.0.0.7:8080", instances.get(1).endpoint().uri().toString());
        assertEquals("orders", instances.get(0).serviceId());
    }

    @Test
    void instanceIdsFollowTheDefaultRegistrationShape() {
        var discovery = discovery(answers("orders.svc", "10.0.0.5"),
            DnsDiscovery.AddressFamily.ANY);

        assertEquals("orders@10.0.0.5:8080",
            discovery.instances("orders").getFirst().instanceId());
    }

    @Test
    void theSnapshotIsOrderedIndependentlyOfTheAnswerOrder() {
        var forward = discovery(answers("orders.svc", "10.0.0.5", "10.0.0.7"),
            DnsDiscovery.AddressFamily.ANY);
        var reverse = discovery(answers("orders.svc", "10.0.0.7", "10.0.0.5"),
            DnsDiscovery.AddressFamily.ANY);

        assertEquals(forward.instances("orders"), reverse.instances("orders"));
        assertThrows(UnsupportedOperationException.class,
            () -> forward.instances("orders").add(forward.instances("orders").getFirst()));
    }

    @Test
    void duplicateAddressesCollapseIntoOneInstance() {
        var discovery = discovery(answers("orders.svc", "10.0.0.5", "10.0.0.5"),
            DnsDiscovery.AddressFamily.ANY);

        assertEquals(1, discovery.instances("orders").size());
    }

    @Test
    void theFamilyFilterNarrowsTheAnswer() {
        var resolver = answers("orders.svc", "10.0.0.5", "::1");

        assertEquals(2, discovery(resolver, DnsDiscovery.AddressFamily.ANY)
            .instances("orders").size());
        assertEquals(1, discovery(resolver, DnsDiscovery.AddressFamily.IPV4)
            .instances("orders").size());
        var ipv6 = discovery(resolver, DnsDiscovery.AddressFamily.IPV6).instances("orders");
        assertEquals(1, ipv6.size());
        assertEquals("http://[0:0:0:0:0:0:0:1]:8080", ipv6.getFirst().endpoint().uri().toString());
    }

    @Test
    void anAnswerFullyFilteredOutIsAnEmptySuccess() {
        var discovery = discovery(answers("orders.svc", "::1"), DnsDiscovery.AddressFamily.IPV4);

        assertTrue(discovery.instances("orders").isEmpty());
    }

    @Test
    void anUnknownServiceDoesNotResolve() {
        var discovery = discovery(host -> {
            throw new AssertionError("unknown service ids must not reach the resolver");
        }, DnsDiscovery.AddressFamily.ANY);

        assertTrue(discovery.instances("unknown").isEmpty());
    }

    @Test
    void aFailedResolutionIsUncheckedAndNamesServiceAndHost() {
        var discovery = discovery(host -> {
            throw new UnknownHostException(host);
        }, DnsDiscovery.AddressFamily.ANY);

        var failure = assertThrows(DnsDiscovery.ResolutionException.class,
            () -> discovery.instances("orders"));
        assertEquals("orders", failure.serviceId());
        assertEquals("orders.svc", failure.host());
        assertTrue(failure.getMessage().contains("orders.svc"), failure.getMessage());
    }

    @Test
    void configMapsServiceIdsToNames() {
        var symbols = symbols("orders=http://orders.svc:8080/bff", null);
        var discovery = DnsDiscovery.of(symbols, answers("orders.svc", "10.0.0.5"), null);

        var instance = discovery.instances("orders").getFirst();
        assertEquals("http://10.0.0.5:8080/bff", instance.endpoint().uri().toString());
    }

    @Test
    void configRequiresAtLeastOneEntry() {
        assertThrows(IllegalArgumentException.class,
            () -> DnsDiscovery.of(symbols(null, null), HostResolver.system(), null));
        assertThrows(IllegalArgumentException.class,
            () -> DnsDiscovery.of(symbols(" , ,", null), HostResolver.system(), null));
    }

    @Test
    void configRejectsAnIpLiteralHostAndPointsAtStaticDiscovery() {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> DnsDiscovery.of(
                symbols("orders=http://10.0.0.5:8080", null), HostResolver.system(), null));

        assertTrue(failure.getMessage().contains("StaticModule"), failure.getMessage());
        assertTrue(failure.getMessage().contains(DnsModule.ConfigKeys.SERVICES),
            failure.getMessage());
    }

    @Test
    void configRejectsARepeatedServiceId() {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> DnsDiscovery.of(
                symbols("orders=http://orders-a.svc:8080,orders=http://orders-b.svc:8080", null),
                HostResolver.system(), null));

        assertTrue(failure.getMessage().contains("duplicate service id"), failure.getMessage());
    }

    @Test
    void configReadsTheFamilyToken() {
        var symbols = symbols("orders=http://orders.svc:8080", "ipv4");

        var discovery = DnsDiscovery.of(
            symbols, answers("orders.svc", "10.0.0.5", "::1"), null);

        assertEquals(1, discovery.instances("orders").size());
    }

    @Test
    void anUnknownFamilyTokenFailsNamingTheKey() {
        var failure = assertThrows(IllegalArgumentException.class,
            () -> DnsDiscovery.of(
                symbols("orders=http://orders.svc:8080", "ipv8"), HostResolver.system(), null));

        assertTrue(failure.getMessage().contains(DnsModule.ConfigKeys.FAMILY),
            failure.getMessage());
    }

    private static SymbolSource symbols(String services, String family) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        if (services != null) {
            values.put(DnsModule.ConfigKeys.SERVICES, services);
        }
        if (family != null) {
            values.put(DnsModule.ConfigKeys.FAMILY, family);
        }
        return SymbolSource.of(
            new CoercerDefault(),
            SymbolProvider.of(() -> values, 0));
    }
}
