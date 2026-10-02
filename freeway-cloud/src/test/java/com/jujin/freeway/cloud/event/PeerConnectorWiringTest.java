package com.jujin.freeway.cloud.event;

import com.jujin.freeway.cloud.CloudModule.ConfigKeys;
import com.jujin.freeway.cloud.CloudModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The connector's optional inputs are one value with named knobs, so a call
 * site cannot swap two same-typed neighbours without the compiler noticing.
 */
class PeerConnectorWiringTest {

    @Test
    void defaultsAreTheConfigKeysDefaults() {
        PeerConnector.Wiring wiring = PeerConnector.Wiring.defaults();

        assertEquals(Duration.ofMillis(ConfigKeys.EVENT_CONNECT_TIMEOUT_MS_DEFAULT),
            wiring.connectTimeout());
        assertEquals(Duration.ofMillis(ConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT),
            wiring.handshakeTimeout());
        assertEquals(ConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT, wiring.backoffBaseMs());
        assertEquals(ConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT, wiring.backoffMaxMs());
        assertEquals("ws", wiring.scheme(), "plaintext unless TLS is asked for");
        assertNull(wiring.sslContext(), "null means the JDK default trust/identity");
    }

    @Test
    void withersAreIndependentAndNormalizeWhatTheyCarry() {
        PeerConnector.Wiring wiring = PeerConnector.Wiring.defaults()
            .withConnectTimeout(Duration.ofSeconds(7))
            .withScheme("")
            .withBackoff(50, 900)
            .withSslContext(null);

        assertEquals(Duration.ofSeconds(7), wiring.connectTimeout());
        assertEquals("ws", wiring.scheme(), "a blank scheme falls back to ws");
        // The two backoff values are one call: swapping them is not expressible.
        assertEquals(50, wiring.backoffBaseMs());
        assertEquals(900, wiring.backoffMaxMs());
        assertEquals(Duration.ofMillis(ConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT),
            wiring.handshakeTimeout(), "an untouched knob keeps its default");

        // A non-positive backoff is a missing value, not a schedule.
        assertEquals(ConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT,
            wiring.withBackoff(0, -1).backoffBaseMs());
        assertEquals(ConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT,
            wiring.withBackoff(0, -1).backoffMaxMs());
    }

    /**
     * A cap below the base is not a cap. The curve clamps every attempt to the
     * cap, so {@code withBackoff(60_000, 1_000)} produced waits of at most one
     * millisecond — a dial loop that re-dials as fast as the network allows,
     * which is the failure the two values exist to schedule against.
     * {@code RetryerDefault} refuses the same pair in its constructor; one
     * shared curve must not be guarded by one caller and not the other.
     */
    @Test
    void aCapBelowTheBaseIsRefused() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> PeerConnector.Wiring.defaults().withBackoff(60_000, 1_000));
        assertTrue(ex.getMessage().contains("backoffMaxMs must be >= backoffBaseMs"),
            ex.getMessage());
    }

    @Test
    void aCapEqualToTheBaseIsAccepted() {
        assertEquals(1_000,
            PeerConnector.Wiring.defaults().withBackoff(1_000, 1_000).backoffMaxMs());
    }
}
