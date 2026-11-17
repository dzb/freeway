package com.jujin.freeway.cloud.member;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node's externally visible identity: the scheme follows the HTTP server's
 * transport, the host is derived only when it is safe to derive, and an
 * explicit value always wins.
 */
class NodeIdentityTest {

    // ==================== scheme ====================

    @Test
    void schemeAutoFollowsTheServerTransport() {
        assertEquals("https", NodeIdentity.scheme("auto", true, "svc"));
        assertEquals("http", NodeIdentity.scheme("auto", false, "svc"));
        assertEquals("http", NodeIdentity.scheme("", false, "svc"),
            "blank means auto too");
        assertEquals("https", NodeIdentity.scheme(null, true, "svc"));
    }

    @Test
    void explicitSchemeWinsAndIsCaseInsensitive() {
        assertEquals("http", NodeIdentity.scheme("HTTP", true, "svc"),
            "an explicit scheme outranks the server's transport");
        assertEquals("https", NodeIdentity.scheme(" https ", false, "svc"));
    }

    @Test
    void unreadableSchemeFailsNamingTheKey() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> NodeIdentity.scheme("grpc", false, "svc"));
        assertTrue(failure.getMessage().contains("member.service-scheme"),
            "the failure must name the key: " + failure.getMessage());
    }

    // ==================== host ====================

    @Test
    void explicitHostWinsEvenWhenItIsBindAll() {
        assertEquals("0.0.0.0", NodeIdentity.host("0.0.0.0", "127.0.0.1", "svc"),
            "an explicit host is the deployment's decision, not a thing to derive past");
        assertEquals("orders.internal", NodeIdentity.host(" orders.internal ", "0.0.0.0", "svc"));
    }

    @Test
    void boundAddressIsKeptBecauseTheServerListensNowhereElse() {
        assertEquals("127.0.0.1",
            NodeIdentity.deriveHost("127.0.0.1", "10.2.3.4", "10.2.3.4", "svc"));
        assertEquals("10.2.3.4",
            NodeIdentity.deriveHost("10.2.3.4", null, null, "svc"));
    }

    @Test
    void bindAllPrefersPodIpThenALocalAddress() {
        assertEquals("10.9.9.9", NodeIdentity.deriveHost("0.0.0.0", "10.9.9.9", "10.1.1.1", "svc"),
            "the platform-injected address is the most specific answer");
        assertEquals("10.1.1.1", NodeIdentity.deriveHost("0.0.0.0", null, "10.1.1.1", "svc"));
        assertEquals("10.1.1.1", NodeIdentity.deriveHost("::", "  ", " 10.1.1.1 ", "svc"));
    }

    @Test
    void bindAllSurvivesWhenNothingCanBeDerived() {
        assertEquals("0.0.0.0", NodeIdentity.deriveHost("0.0.0.0", null, null, "svc"),
            "no candidate: keep the bind address — the caller warns about it");
        assertEquals("0.0.0.0", NodeIdentity.deriveHost("0.0.0.0", "0.0.0.0", "", "svc"),
            "a bind-all candidate is not an answer");
    }

    @Test
    void bindAllDetectionMatchesTheDeclarationWarning() {
        assertTrue(NodeIdentity.isBindAll(null));
        assertTrue(NodeIdentity.isBindAll(""));
        assertTrue(NodeIdentity.isBindAll(" 0.0.0.0 "));
        assertTrue(NodeIdentity.isBindAll("::"));
        assertTrue(NodeIdentity.isBindAll("[::]"));
        assertFalse(NodeIdentity.isBindAll("127.0.0.1"));
        assertFalse(NodeIdentity.isBindAll("10.2.3.4"));
    }
}
