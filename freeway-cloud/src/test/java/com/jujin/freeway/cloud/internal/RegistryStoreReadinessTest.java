package com.jujin.freeway.cloud.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.Health;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Readiness is a fact the store can hold and discovery must honour: the seam
 * promises "live <em>and ready</em>" instances, so a registered-but-warming
 * instance is present in the table and absent from discovery until it flips.
 * Without this the contract's "ready" half was decorative — every registration
 * asserted {@code Health.up()}.
 */
class RegistryStoreReadinessTest {

    private static final Duration TTL = Duration.ofSeconds(30);
    private static final ServiceInstance INSTANCE =
        ServiceInstance.of("svc", "i1", Endpoint.of("http", "127.0.0.1", 8080));

    @Test
    void a_starting_instance_is_not_discoverable_until_it_is_ready() {
        RegistryStore store = new RegistryStore();

        store.register(INSTANCE, Health.starting());

        assertEquals(0, store.liveReady("svc", TTL).size(),
            "a warming instance must not be handed to callers");
        assertEquals(1, store.instanceCount(), "but it is registered, not missing");

        assertTrue(store.markReady("svc", "i1", true));
        assertEquals(1, store.liveReady("svc", TTL).size(),
            "the flip is what makes it discoverable");
    }

    @Test
    void a_heartbeat_carries_the_readiness_it_observed() {
        RegistryStore store = new RegistryStore();
        store.register(INSTANCE, Health.starting());

        assertTrue(store.renew("svc", "i1", true), "the heartbeat lands");
        assertEquals(1, store.liveReady("svc", TTL).size(),
            "readiness on the heartbeat flips the instance without a second call");

        assertTrue(store.renew("svc", "i1", false), "and back");
        assertEquals(0, store.liveReady("svc", TTL).size(),
            "a heartbeat that reports not-ready takes it out of rotation again");
    }

    @Test
    void readiness_for_an_unknown_instance_is_refused_not_invented() {
        RegistryStore store = new RegistryStore();
        assertEquals(false, store.markReady("svc", "ghost", true),
            "the store must not create an entry from a readiness publish");
        assertEquals(0, store.instanceCount());
    }
}
