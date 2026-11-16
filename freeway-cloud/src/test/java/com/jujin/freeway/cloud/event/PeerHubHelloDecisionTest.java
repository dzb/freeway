package com.jujin.freeway.cloud.event;

import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mesh hello admission: the handshake gate is pure (no session) so it is
 * testable separately from the running inbound gate ({@code receive}).
 */
class PeerHubHelloDecisionTest {

    private static JsonObject hello(String origin, String token, Object subscribe) {
        JsonObject frame = JsonUtils.object().put("proto", 1);
        if (origin != null) {
            frame.put("origin", origin);
        }
        if (token != null) {
            frame.put("token", token);
        }
        if (subscribe != null) {
            frame.put("subscribe", subscribe);
        }
        return frame;
    }

    @Test
    void missingOriginIsRejected() {
        PeerHub.HelloDecision decision = PeerHub.validateHello(hello(null, null, null), "");
        assertFalse(decision.accepted());
        assertEquals(1002, decision.closeCode());
    }

    @Test
    void tokenMismatchIsRejectedWithOriginForTheLog() {
        PeerHub.HelloDecision decision = PeerHub.validateHello(
            hello("peer-a", "wrong", List.of()), "s3cret");
        assertFalse(decision.accepted());
        assertEquals(1008, decision.closeCode());
        assertEquals("peer-a", decision.origin(), "origin kept for the rejection log");
    }

    @Test
    void absentTokenPassesWhenNoTokenConfigured() {
        PeerHub.HelloDecision decision = PeerHub.validateHello(
            hello("peer-a", null, List.of("greet.")), "");
        assertTrue(decision.accepted());
        assertEquals("peer-a", decision.origin());
        assertEquals(List.of("greet."), decision.subscriptions());
    }

    @Test
    void acceptedHelloCarriesParsedSubscriptions() {
        PeerHub.HelloDecision decision = PeerHub.validateHello(
            hello("peer-a", "s3cret", List.of("greet.", "order.")), "s3cret");
        assertTrue(decision.accepted());
        assertEquals("peer-a", decision.origin());
        assertEquals(List.of("greet.", "order."), decision.subscriptions());
    }
}
