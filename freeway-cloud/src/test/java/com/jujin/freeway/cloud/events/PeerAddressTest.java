package com.jujin.freeway.cloud.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Peer address parsing: {@code host:port} strings resolve to mesh WebSocket
 * URIs, including IPv6 literals in brackets and bare.
 */
class PeerAddressTest {

    @Test
    void peerUrisCoverPlainHostPortAndIpv6() {
        assertEquals("ws://host1:7001/cloud/events", PeerAddress.parse("host1:7001").toUri("ws").toString());
        assertEquals("ws://host1:80/cloud/events", PeerAddress.parse("host1").toUri("ws").toString(),
            "no port component → the ws default port");
        assertEquals("ws://[::1]:7001/cloud/events", PeerAddress.parse("[::1]:7001").toUri("ws").toString(),
            "bracketed IPv6 with a port");
        assertEquals("ws://[fe80::1]:80/cloud/events", PeerAddress.parse("fe80::1").toUri("ws").toString(),
            "a bare IPv6 literal (multiple colons) carries no port component");
    }

    @Test
    void malformedPeerAddressesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("[::1:7001"),
            "unclosed IPv6 bracket");
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("host:notaport"));
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("host:99999"),
            "port out of range");
        assertThrows(IllegalArgumentException.class, () -> PeerAddress.parse("  "),
            "blank peer address");
    }

    @Test
    void endpointIdentityIsHostAndPort() {
        assertEquals(PeerAddress.parse("host1:7001"), PeerAddress.parse("host1:7001"));
        assertEquals(PeerAddress.parse("host1"), PeerAddress.parse("host1:80"),
            "absent port and explicit default port are the same endpoint");
        assertEquals("host1:7001", PeerAddress.parse("host1:7001").toString());
        assertEquals("[::1]:7001", PeerAddress.parse("[::1]:7001").toString());
    }
}
