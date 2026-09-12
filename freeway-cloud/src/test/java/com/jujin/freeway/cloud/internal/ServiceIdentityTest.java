package com.jujin.freeway.cloud.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The node's externally visible identity: the scheme follows the HTTP server's
 * transport, the host is derived only when it is safe to derive, and an
 * explicit value always wins.
 */
class ServiceIdentityTest {

    // ==================== scheme ====================

    @Test
    void schemeAutoFollowsTheServerTransport() {
        assertEquals("https", ServiceIdentity.scheme("auto", true, "svc"));
        assertEquals("http", ServiceIdentity.scheme("auto", false, "svc"));
        assertEquals("http", ServiceIdentity.scheme("", false, "svc"),
            "blank means auto too");
        assertEquals("https", ServiceIdentity.scheme(null, true, "svc"));
    }

    @Test
    void explicitSchemeWinsAndIsCaseInsensitive() {
        assertEquals("http", ServiceIdentity.scheme("HTTP", true, "svc"),
            "an explicit scheme outranks the server's transport");
        assertEquals("https", ServiceIdentity.scheme(" https ", false, "svc"));
    }

    @Test
    void unreadableSchemeFailsNamingTheKey() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> ServiceIdentity.scheme("grpc", false, "svc"));
        assertTrue(failure.getMessage().contains("registry.service-scheme"),
            "the failure must name the key: " + failure.getMessage());
    }

    // ==================== host ====================

    @Test
    void explicitHostWinsEvenWhenItIsBindAll() {
        assertEquals("0.0.0.0", ServiceIdentity.host("0.0.0.0", "127.0.0.1", "svc"),
            "an explicit host is the deployment's decision, not a thing to derive past");
        assertEquals("orders.internal", ServiceIdentity.host(" orders.internal ", "0.0.0.0", "svc"));
    }

    @Test
    void boundAddressIsKeptBecauseTheServerListensNowhereElse() {
        assertEquals("127.0.0.1",
            ServiceIdentity.deriveHost("127.0.0.1", "10.2.3.4", "10.2.3.4", "svc"));
        assertEquals("10.2.3.4",
            ServiceIdentity.deriveHost("10.2.3.4", null, null, "svc"));
    }

    @Test
    void bindAllPrefersPodIpThenALocalAddress() {
        assertEquals("10.9.9.9", ServiceIdentity.deriveHost("0.0.0.0", "10.9.9.9", "10.1.1.1", "svc"),
            "the platform-injected address is the most specific answer");
        assertEquals("10.1.1.1", ServiceIdentity.deriveHost("0.0.0.0", null, "10.1.1.1", "svc"));
        assertEquals("10.1.1.1", ServiceIdentity.deriveHost("::", "  ", " 10.1.1.1 ", "svc"));
    }

    @Test
    void bindAllSurvivesWhenNothingCanBeDerived() {
        assertEquals("0.0.0.0", ServiceIdentity.deriveHost("0.0.0.0", null, null, "svc"),
            "no candidate: keep the bind address — the caller warns about it");
        assertEquals("0.0.0.0", ServiceIdentity.deriveHost("0.0.0.0", "0.0.0.0", "", "svc"),
            "a bind-all candidate is not an answer");
    }

    @Test
    void bindAllDetectionMatchesTheDeclarationWarning() {
        assertTrue(ServiceIdentity.isBindAll(null));
        assertTrue(ServiceIdentity.isBindAll(""));
        assertTrue(ServiceIdentity.isBindAll(" 0.0.0.0 "));
        assertTrue(ServiceIdentity.isBindAll("::"));
        assertTrue(ServiceIdentity.isBindAll("[::]"));
        assertFalse(ServiceIdentity.isBindAll("127.0.0.1"));
        assertFalse(ServiceIdentity.isBindAll("10.2.3.4"));
    }
}
