package com.jujin.freeway.cloud.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceDeclaration;
import com.jujin.freeway.cloud.discovery.ServiceDiscovery;
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

            @Override public void renew(String serviceId, String instanceId) { }

            @Override public void unregister(ServiceInstance instance) { }
        };
        // A registry that never lists us — the "entry was evicted" case.
        ServiceDiscovery blind = serviceId -> List.of();

        RegistryRenewal renewal = new RegistryRenewal();
        try (Container container = Freeway.create(binder -> {
            binder.bind(ServiceRegistry.class).to(c -> registry);
            binder.bind(ServiceDiscovery.class).to(c -> blind);
            binder.contribute(ServiceDeclaration.class)
                .add("test", c -> INSTANCE);
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

            @Override public void renew(String serviceId, String instanceId) { }

            @Override public void unregister(ServiceInstance instance) { }
        };
        ServiceDiscovery discovery = serviceId -> List.copyOf(live);

        RegistryRenewal renewal = new RegistryRenewal();
        try (Container container = Freeway.create(binder -> {
            binder.bind(ServiceRegistry.class).to(c -> registry);
            binder.bind(ServiceDiscovery.class).to(c -> discovery);
            binder.contribute(ServiceDeclaration.class).add("test", c -> INSTANCE);
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
    void nothingRegisteredMeansNothingToGuard() {
        // No declaration resolved: the contributor must not claim a healthy
        // registry it never registered with.
        RegistryRenewal renewal = new RegistryRenewal();
        assertFalse(renewal.isTracking());
        assertTrue(renewal.isHealthy(), "an untracked renewal is not a failure");
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
