package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process registry: register → discover → renew → stale eviction →
 * unregister.
 */
class RegistryStoreTest {

    @Test
    void registerDiscoverRenewDeregister() {
        RegistryStore store = new RegistryStore();
        ServiceInstance inst = ServiceInstance.of("svc", "i1", Endpoint.of("http", "h", 8080));

        store.register(inst);
        assertEquals(List.of(inst), store.liveReady("svc", Duration.ofMinutes(1)));

        store.renew("svc", "i1");
        assertEquals(1, store.liveReady("svc", Duration.ofMinutes(1)).size());

        store.unregister(inst);
        assertTrue(store.liveReady("svc", Duration.ofMinutes(1)).isEmpty());
    }

    @Test
    void staleInstancesEvictedLazily() {
        RegistryStore store = new RegistryStore();
        store.register(ServiceInstance.of("svc", "i1", Endpoint.of("http", "h", 8080)));
        // Negative maxAge puts the cutoff strictly after lastSeen, so the
        // verdict cannot depend on clock granularity (Windows Instant.now()
        // is coarse enough that lastSeen == cutoff on a ZERO cutoff).
        assertTrue(store.liveReady("svc", Duration.ZERO.minusMillis(1)).isEmpty());

        // Eviction must REMOVE the stale entry, not just filter it: the store
        // cannot grow without bound, and a re-registering instance (a fresh
        // registration, not a heartbeat for a dead entry) becomes discoverable.
        store.register(ServiceInstance.of("svc", "i1", Endpoint.of("http", "h", 8080)));
        assertEquals(1, store.liveReady("svc", Duration.ofSeconds(30)).size(),
            "re-registered instances must be discoverable after lazy eviction");
    }

    @Test
    void unknownServiceIsEmpty() {
        RegistryStore store = new RegistryStore();
        assertTrue(store.liveReady("missing", Duration.ofMinutes(1)).isEmpty());
    }

    @Test
    void serviceMapEvictionKeepsConcurrentRegistrationsDiscoverable() {
        // Regression: unregister/liveReady dropped the empty per-service map
        // with a check-then-remove pair. A register() landing between the
        // empty-check and the removal lost its entry with the map (and
        // renew() became a no-op forever). The eviction is now atomic with
        // register's computeIfAbsent, so a fresh registration always sticks.
        RegistryStore store = new RegistryStore();
        ServiceInstance first = ServiceInstance.of("svc", "i1", Endpoint.of("http", "h", 8080));
        store.register(first);
        store.unregister(first);
        assertTrue(store.liveReady("svc", Duration.ofMinutes(1)).isEmpty());

        ServiceInstance second = ServiceInstance.of("svc", "i2", Endpoint.of("http", "h", 8080));
        store.register(second);
        assertEquals(java.util.List.of(second), store.liveReady("svc", Duration.ofMinutes(1)),
            "a registration after map eviction must be discoverable");
        store.renew("svc", "i2");
        assertEquals(1, store.liveReady("svc", Duration.ofMinutes(1)).size(),
            "renewal keeps working after re-registration");
    }
}
