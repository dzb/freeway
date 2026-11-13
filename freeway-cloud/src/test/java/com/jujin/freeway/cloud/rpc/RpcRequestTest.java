package com.jujin.freeway.cloud.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idempotency derivation on {@link RpcRequest}: the verb class is the
 * default, an explicit marker overrides it, and the resilience loop's
 * replay-safety verdict rides on the record.
 */
class RpcRequestTest {

    @Test
    void safeAndIdempotentVerbsDeriveToTrue() {
        for (String verb : new String[]{"GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE"}) {
            RpcRequest request = new RpcRequest(verb, "/x", null, null);
            assertTrue(request.idempotent(), verb + " must derive to idempotent");
        }
    }

    @Test
    void postPatchAndUnknownVerbsDeriveToFalse() {
        for (String verb : new String[]{"POST", "PATCH", "BREW"}) {
            RpcRequest request = new RpcRequest(verb, "/x", null, null);
            assertFalse(request.idempotent(),
                verb + " must derive to non-idempotent (conservative default)");
        }
    }

    @Test
    void verbDerivationIsCaseInsensitive() {
        assertTrue(new RpcRequest("get", "/x", null, null).idempotent());
        assertFalse(new RpcRequest("post", "/x", null, null).idempotent());
    }

    @Test
    void explicitMarkerOverridesTheVerbClass() {
        assertTrue(RpcRequest.post("/x", new byte[0], "application/json")
            .withIdempotent(true).idempotent(), "an explicitly marked POST may be replayed");
        assertFalse(new RpcRequest("GET", "/x", null, null)
            .withIdempotent(false).idempotent(), "an explicitly unmarked GET must not be replayed");
    }

    @Test
    void witherPreservesTheRestOfTheRequest() {
        RpcRequest request = RpcRequest.post("/api/x", "[]")
            .withIdempotent(true);
        assertEquals("POST", request.method());
        assertEquals("/api/x", request.path());
        assertEquals("application/json", request.headers().get("Content-Type"));
    }

    @Test
    void verbHelperMatchesTheDerivedDefault() {
        assertEquals(new RpcRequest("POST", "/x", null, null).idempotent(),
            RpcRequest.isIdempotentMethod("post"));
        assertEquals(new RpcRequest("PUT", "/x", null, null).idempotent(),
            RpcRequest.isIdempotentMethod("put"));
        assertFalse(RpcRequest.isIdempotentMethod(null), "null verb is conservative");
    }
}
