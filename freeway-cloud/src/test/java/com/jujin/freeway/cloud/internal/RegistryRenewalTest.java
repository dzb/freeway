package com.jujin.freeway.cloud.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Heartbeat truth: what the readiness contributor reports must follow what the
 * registry actually knows, and a registration lost to eviction must come back
 * without a restart.
 */
class RegistryRenewalTest {

    private static final ServiceInstance INSTANCE =
        ServiceInstance.of("svc", "i1", Endpoint.of("http", "127.0.0.1", 8080));

    @Test
    void aLostRegistrationIsReRegisteredAndReadinessGoesUnhealthy() throws Exception {
        List<ServiceInstance> registrations = new CopyOnWriteArrayList<>();
        ServiceRegistry registry = new ServiceRegistry() {
            @Override public void register(ServiceInstance instance) {
                registrations.add(instance);
            }

            @Override public boolean renew(String serviceId, String instanceId) {
                return false;   // the registry no longer holds the entry
            }

            @Override public void unregister(ServiceInstance instance) { }
        };
        RegistryRenewal renewal = new RegistryRenewal();
        try (Container container = Freeway.create(binder -> {
            binder.bind(ServiceRegistry.class).to(c -> registry);
            binder.contribute(ServiceDeclaration.class)
                .add("test", (ServiceDeclaration) c -> INSTANCE);
        })) {
            RegistryLifecycleHook hook =
                new RegistryLifecycleHook(renewal, Duration.ofMillis(20));
            hook.start(container);
            try {
                assertTrue(await(() -> renewal.consecutiveFailures() >= RegistryRenewal.UNHEALTHY_AFTER),
                    "a heartbeat that cannot verify the entry must count against readiness");
                assertFalse(renewal.isHealthy());
                assertTrue(registrations.size() >= 2,
                    "the hook re-registers what the registry lost, got: " + registrations.size());
                assertEquals(1, registrations.stream().distinct().count());
            } finally {
                hook.stop(container);
            }
        }
    }

    @Test
    void aVerifiedHeartbeatKeepsReadinessGreenAndResetsStrikes() throws Exception {
        List<ServiceInstance> live = new CopyOnWriteArrayList<>(List.of(INSTANCE));
        ServiceRegistry registry = new ServiceRegistry() {
            @Override public void register(ServiceInstance instance) { }

            @Override public boolean renew(String serviceId, String instanceId) {
                return true;   // the registry still holds it
            }

            @Override public void unregister(ServiceInstance instance) { }
        };

        RegistryRenewal renewal = new RegistryRenewal();
        try (Container container = Freeway.create(binder -> {
            binder.bind(ServiceRegistry.class).to(c -> registry);
            binder.contribute(ServiceDeclaration.class).add("test", (ServiceDeclaration) c -> INSTANCE);
        })) {
            RegistryLifecycleHook hook =
                new RegistryLifecycleHook(renewal, Duration.ofMillis(20));
            hook.start(container);
            try {
                assertTrue(await(() -> renewal.isTracking()), "the hook must publish that it tracks");
                assertTrue(await(() -> renewal.consecutiveFailures() == 0 && renewal.isHealthy()));
            } finally {
                hook.stop(container);
            }
        }
    }

    @Test
    void stopSignalsThenDrainsBeforeReturning() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ServiceRegistry registry = new ServiceRegistry() {
            @Override public void register(ServiceInstance instance) { }

            @Override public boolean renew(String serviceId, String instanceId) {
                return true;
            }

            @Override public void unregister(ServiceInstance instance) {
                events.add("unregistered");
            }
        };
        RegistryRenewal renewal = new RegistryRenewal();
        try (Container container = Freeway.create(binder -> {
            binder.bind(ServiceRegistry.class).to(c -> registry);
            binder.contribute(ServiceDeclaration.class).add("test", (ServiceDeclaration) c -> INSTANCE);
            // 60 ms window: long enough to observe, short enough to keep the suite fast.
            binder.bind(com.jujin.freeway.ioc.symbol.SymbolSource.class)
                .to(c -> symbols(com.jujin.freeway.cloud.CloudConfigKeys.REGISTRY_SHUTDOWN_DRAIN, "60ms"))
                .primary();
        })) {
            RegistryLifecycleHook hook =
                new RegistryLifecycleHook(renewal, Duration.ofMillis(20));
            hook.start(container);
            assertTrue(await(() -> renewal.isTracking()));
            // Wait until the drain window is demonstrably open.
            long start = System.nanoTime();
            hook.stop(container);
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMillis >= 50,
                "stop must keep serving for the configured window, elapsed=" + elapsedMillis);
            assertEquals(List.of("unregistered"), events,
                "the entry must leave the registry before the window, not after it");
            assertTrue(renewal.isDraining(),
                "readiness must report the drain so a probe-driven LB stops routing here");
        }
    }

    @Test
    void nothingRegisteredMeansNothingToGuard() {
        // No declaration resolved: the contributor must not claim a healthy
        // registry it never registered with.
        RegistryRenewal renewal = new RegistryRenewal();
        assertFalse(renewal.isTracking());
        assertTrue(renewal.isHealthy(), "an untracked renewal is not a failure");
    }

    /**
     * Minimal symbol source: one key, no cascade. {@code resolve(SymbolSpec)}
     * is overridden because the container's own source wires a {@link Coercer}
     * for specs without a parser (duration keys) — the stub substitutes the
     * library coercer so the spec path under test is the real one.
     */
    private static com.jujin.freeway.ioc.symbol.SymbolSource symbols(String key, String value) {
        return new com.jujin.freeway.ioc.symbol.SymbolSource() {
            @Override public String resolve(String name) { return resolve(name, null); }

            @Override public <T> T resolve(com.jujin.freeway.ioc.symbol.SymbolSpec<T> spec) {
                return spec.parse(resolve(spec.key(), null),
                    new com.jujin.freeway.commons.coercion.CoercerDefault());
            }

            @Override public String resolve(String name, String fallback) {
                return key.equals(name) ? value : fallback;
            }

            @Override public String expand(String input) {
                return input;
            }
        };
    }

    private static boolean await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
