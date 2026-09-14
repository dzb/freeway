package com.jujin.freeway.cloud.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jujin.freeway.cloud.CloudConfigKeys;
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

        assertEquals(Duration.ofMillis(CloudConfigKeys.EVENT_CONNECT_TIMEOUT_MS_DEFAULT),
            wiring.connectTimeout());
        assertEquals(Duration.ofMillis(CloudConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT),
            wiring.handshakeTimeout());
        assertEquals(CloudConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT, wiring.backoffBaseMs());
        assertEquals(CloudConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT, wiring.backoffMaxMs());
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
        assertEquals(Duration.ofMillis(CloudConfigKeys.EVENT_HANDSHAKE_TIMEOUT_MS_DEFAULT),
            wiring.handshakeTimeout(), "an untouched knob keeps its default");

        // A non-positive backoff is a missing value, not a schedule.
        assertEquals(CloudConfigKeys.EVENT_BACKOFF_BASE_MS_DEFAULT,
            wiring.withBackoff(0, -1).backoffBaseMs());
        assertEquals(CloudConfigKeys.EVENT_BACKOFF_MAX_MS_DEFAULT,
            wiring.withBackoff(0, -1).backoffMaxMs());
    }
}
