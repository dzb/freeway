package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.internal.RegistryStore;
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
        // Zero-age cutoff: lastSeen (registration instant) is before cutoff (now).
        assertTrue(store.liveReady("svc", Duration.ZERO).isEmpty());

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
}
