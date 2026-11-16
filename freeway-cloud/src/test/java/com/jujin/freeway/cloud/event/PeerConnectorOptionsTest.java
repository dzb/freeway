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
class PeerConnectorOptionsTest {

    @Test
    void defaultsAreTheConfigKeysDefaults() {
        PeerConnector.Options options = PeerConnector.Options.defaults();

        assertEquals(Duration.ofMillis(EventMeshModule.ConfigKeys.EVENT_CONNECT_TIMEOUT_MS_DEFAULT),
            options.connectTimeout());
        assertEquals(Duration.ofMillis(EventMeshModule.ConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT),
            options.handshakeTimeout());
        assertEquals(EventMeshModule.ConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT, options.backoffBaseMs());
        assertEquals(EventMeshModule.ConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT, options.backoffMaxMs());
        assertEquals("ws", options.scheme(), "plaintext unless TLS is asked for");
        assertNull(options.sslContext(), "null means the JDK default trust/identity");
    }

    @Test
    void withersAreIndependentAndNormalizeWhatTheyCarry() {
        PeerConnector.Options options = PeerConnector.Options.defaults()
            .withConnectTimeout(Duration.ofSeconds(7))
            .withScheme("")
            .withBackoff(50, 900)
            .withSslContext(null);

        assertEquals(Duration.ofSeconds(7), options.connectTimeout());
        assertEquals("ws", options.scheme(), "a blank scheme falls back to ws");
        // The two backoff values are one call: swapping them is not expressible.
        assertEquals(50, options.backoffBaseMs());
        assertEquals(900, options.backoffMaxMs());
        assertEquals(Duration.ofMillis(EventMeshModule.ConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT),
            options.handshakeTimeout(), "an untouched knob keeps its default");

        // A non-positive backoff is a missing value, not a schedule.
        assertEquals(EventMeshModule.ConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT,
            options.withBackoff(0, -1).backoffBaseMs());
        assertEquals(EventMeshModule.ConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT,
            options.withBackoff(0, -1).backoffMaxMs());
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
            () -> PeerConnector.Options.defaults().withBackoff(60_000, 1_000));
        assertTrue(ex.getMessage().contains("backoffMaxMs must be >= backoffBaseMs"),
            ex.getMessage());
    }

    @Test
    void aCapEqualToTheBaseIsAccepted() {
        assertEquals(1_000,
            PeerConnector.Options.defaults().withBackoff(1_000, 1_000).backoffMaxMs());
    }
}
