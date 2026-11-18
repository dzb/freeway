package com.jujin.freeway.cloud.discovery;

import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.KnownKeys;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsModuleTest {

    private static final ServiceEndpoint ORDERS = ServiceEndpoint.of("http", "orders.svc", 8080);

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

    private static HostResolver answering(AtomicInteger calls, String host, String... literals) {
        return requested -> {
            calls.incrementAndGet();
            if (!requested.equals(host)) {
                throw new UnknownHostException(requested);
            }
            return addresses(literals);
        };
    }

    private static DnsModule module(HostResolver resolver) {
        var discovery = new DnsDiscovery(
            Map.of("orders", ORDERS), resolver, DnsDiscovery.AddressFamily.ANY);
        return DnsModule.of(discovery);
    }

    @Test
    void programmaticMapBindsDiscoveryAndTheBalancerWithoutARegistry() {
        try (var container = Freeway.create(
                module(answering(new AtomicInteger(), "orders.svc", "10.0.0.5", "10.0.0.7")))) {
            var discovery = container.get(ServiceDiscovery.class);

            assertEquals(2, discovery.instances("orders").size());
            assertTrue(container.get(LoadBalancer.class).choose(discovery.instances("orders"))
                .isPresent());
            assertThrows(IllegalArgumentException.class,
                () -> container.get(ServiceRegistry.class));
        }
    }

    @Test
    void configDrivenModuleDeclaresItsVocabulary() {
        System.setProperty(DnsModule.ConfigKeys.SERVICES, "orders=http://orders.svc:8080");
        try (var container = Freeway.create(new DnsModule())) {
            var declared = container.extension(KnownKeys.class).all().stream()
                .flatMap(keys -> keys.keys().stream())
                .toList();

            assertTrue(declared.contains(DnsModule.ConfigKeys.SERVICES), declared.toString());
            assertTrue(declared.contains(DnsModule.ConfigKeys.REFRESH), declared.toString());
            assertTrue(declared.contains(DnsModule.ConfigKeys.FAMILY), declared.toString());
            // Config parsing happens at binding realization; no name is resolved here.
            assertTrue(container.get(ServiceDiscovery.class).instances("unknown").isEmpty());
        }
    }

    @Test
    void configDrivenModuleFailsWhenNoServicesAreConfigured() {
        try (var container = Freeway.create(new DnsModule())) {
            // get() may hand back a scope proxy; invoking a method realizes the provider.
            assertThrows(IllegalArgumentException.class,
                () -> container.get(ServiceDiscovery.class).instances("orders"));
        }
    }

    @Test
    void configDrivenModuleRejectsAnIpLiteralAtBinding() {
        System.setProperty(DnsModule.ConfigKeys.SERVICES, "orders=http://10.0.0.5:8080");
        try (var container = Freeway.create(new DnsModule())) {
            assertThrows(IllegalArgumentException.class,
                () -> container.get(ServiceDiscovery.class).instances("orders"));
        }
    }

    @Test
    void configValidationHookFailsStartupOnAGrammarError() throws Exception {
        System.setProperty(DnsModule.ConfigKeys.SERVICES, "orders");
        try (var container = Freeway.create(new DnsModule())) {
            RuntimeHook hook = container.extension(RuntimeHook.class).all().getFirst();
            assertThrows(IllegalArgumentException.class, () -> hook.start(container));
        }
    }

    @Test
    void theStartupWarmUpResolvesOnceAndCachesTheAnswer() throws Exception {
        System.setProperty(DnsModule.ConfigKeys.SERVICES, "orders=http://orders.svc:8080");
        var calls = new AtomicInteger();
        try (var container = Freeway.create(
                DnsModule.configDriven(answering(calls, "orders.svc", "10.0.0.5")))) {
            RuntimeHook hook = container.extension(RuntimeHook.class).all().getFirst();
            hook.start(container);

            assertEquals(1, calls.get(), "the warm-up resolves once");
            assertEquals(1, container.get(ServiceDiscovery.class).instances("orders").size());
            assertEquals(1, calls.get(), "the warm-up answer is cached, not resolved again");
        }
    }

    @Test
    void aFailingStartupResolutionWarnsButDoesNotFailStartup() throws Exception {
        System.setProperty(DnsModule.ConfigKeys.SERVICES, "orders=http://orders.svc:8080");
        try (var container = Freeway.create(DnsModule.configDriven(host -> {
            throw new UnknownHostException(host);
        }))) {
            RuntimeHook hook = container.extension(RuntimeHook.class).all().getFirst();
            hook.start(container); // must not throw: no pods ready is a runtime state

            assertThrows(DnsDiscovery.ResolutionException.class,
                () -> container.get(ServiceDiscovery.class).instances("orders"));
        }
    }

    @Test
    void theDefaultRefreshCachesOneAnswerPerWindow() {
        var calls = new AtomicInteger();
        try (var container = Freeway.create(
                module(answering(calls, "orders.svc", "10.0.0.5")))) {
            var discovery = container.get(ServiceDiscovery.class);
            discovery.instances("orders");
            discovery.instances("orders");

            assertEquals(1, calls.get());
        }
    }

    @Test
    void aZeroRefreshResolvesOnEveryCall() {
        System.setProperty(DnsModule.ConfigKeys.REFRESH, "0");
        var calls = new AtomicInteger();
        try (var container = Freeway.create(
                module(answering(calls, "orders.svc", "10.0.0.5")))) {
            var discovery = container.get(ServiceDiscovery.class);
            discovery.instances("orders");
            discovery.instances("orders");

            assertEquals(2, calls.get());
        }
    }

    @Test
    void aNegativeRefreshFailsAtBinding() {
        System.setProperty(DnsModule.ConfigKeys.REFRESH, "-1s");
        try (var container = Freeway.create(
                module(answering(new AtomicInteger(), "orders.svc", "10.0.0.5")))) {
            assertThrows(IllegalArgumentException.class,
                () -> container.get(ServiceDiscovery.class).instances("orders"));
        }
    }

    @AfterEach
    void clearKeys() {
        System.clearProperty(DnsModule.ConfigKeys.SERVICES);
        System.clearProperty(DnsModule.ConfigKeys.REFRESH);
        System.clearProperty(DnsModule.ConfigKeys.FAMILY);
    }
}
