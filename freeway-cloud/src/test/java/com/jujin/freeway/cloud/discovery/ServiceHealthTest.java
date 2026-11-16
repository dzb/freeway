package com.jujin.freeway.cloud.discovery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ServiceHealthTest {

    @Test
    void readyRequiresLive() {
        assertThrows(IllegalArgumentException.class,
            () -> new ServiceHealth(false, true, Instant.now()));
    }

    @Test
    void liveButNotReadyIsValid() {
        assertDoesNotThrow(() -> new ServiceHealth(true, false, Instant.now()));
    }

    @Test
    void lastSeenIsRequired() {
        assertThrows(NullPointerException.class,
            () -> new ServiceHealth(true, false, null));
    }

    @Test
    void upIsLiveAndReady() {
        ServiceHealth up = ServiceHealth.up();
        assertTrue(up.live());
        assertTrue(up.ready());
        assertNotNull(up.lastSeen());
    }

    @Test
    void startingIsNeitherLiveNorReady() {
        ServiceHealth starting = ServiceHealth.starting();
        assertFalse(starting.live());
        assertFalse(starting.ready());
        assertNotNull(starting.lastSeen());
    }
}
