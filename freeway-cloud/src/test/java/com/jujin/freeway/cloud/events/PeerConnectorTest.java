package com.jujin.freeway.cloud.events;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Peer address parsing: {@code host:port} strings resolve to mesh WebSocket
 * URIs, including IPv6 literals in brackets and bare.
 */
class PeerConnectorTest {

    @Test
    void peerUrisCoverPlainHostPortAndIpv6() {
        try (PeerConnector connector = new PeerConnector(new PeerHub(), List.of(), Duration.ofSeconds(1))) {
            assertEquals("ws://host1:7001/cloud/events", connector.toUri("host1:7001").toString());
            assertEquals("ws://host1:80/cloud/events", connector.toUri("host1").toString(),
                "no port component → the ws default port");
            assertEquals("ws://[::1]:7001/cloud/events", connector.toUri("[::1]:7001").toString(),
                "bracketed IPv6 with a port");
            assertEquals("ws://[fe80::1]:80/cloud/events", connector.toUri("fe80::1").toString(),
                "a bare IPv6 literal (multiple colons) carries no port component");
        }
    }

    @Test
    void malformedPeerAddressesAreRejected() {
        try (PeerConnector connector = new PeerConnector(new PeerHub(), List.of(), Duration.ofSeconds(1))) {
            assertThrows(IllegalArgumentException.class, () -> connector.toUri("[::1:7001"),
                "unclosed IPv6 bracket");
            assertThrows(IllegalArgumentException.class, () -> connector.toUri("host:notaport"));
            assertThrows(IllegalArgumentException.class, () -> connector.toUri("host:99999"),
                "port out of range");
            assertThrows(IllegalArgumentException.class, () -> connector.toUri("  "),
                "blank peer address");
        }
    }
}
