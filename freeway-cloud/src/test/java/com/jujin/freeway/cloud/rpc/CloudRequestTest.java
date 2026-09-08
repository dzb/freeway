package com.jujin.freeway.cloud.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idempotency derivation on {@link CloudRequest}: the verb class is the
 * default, an explicit marker overrides it, and the resilience loop's
 * replay-safety verdict rides on the record.
 */
class CloudRequestTest {

    @Test
    void safeAndIdempotentVerbsDeriveToTrue() {
        for (String verb : new String[]{"GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE"}) {
            CloudRequest request = new CloudRequest(verb, "/x", null, null);
            assertTrue(request.idempotent(), verb + " must derive to idempotent");
        }
    }

    @Test
    void postPatchAndUnknownVerbsDeriveToFalse() {
        for (String verb : new String[]{"POST", "PATCH", "BREW"}) {
            CloudRequest request = new CloudRequest(verb, "/x", null, null);
            assertFalse(request.idempotent(),
                verb + " must derive to non-idempotent (conservative default)");
        }
    }

    @Test
    void verbDerivationIsCaseInsensitive() {
        assertTrue(new CloudRequest("get", "/x", null, null).idempotent());
        assertFalse(new CloudRequest("post", "/x", null, null).idempotent());
    }

    @Test
    void explicitMarkerOverridesTheVerbClass() {
        assertTrue(CloudRequest.post("/x", new byte[0], "application/json")
            .idempotentWith(true).idempotent(), "an explicitly marked POST may be replayed");
        assertFalse(new CloudRequest("GET", "/x", null, null)
            .idempotentWith(false).idempotent(), "an explicitly unmarked GET must not be replayed");
    }

    @Test
    void witherPreservesTheRestOfTheRequest() {
        CloudRequest request = CloudRequest.post("/api/x", "[]")
            .idempotentWith(true);
        assertEquals("POST", request.method());
        assertEquals("/api/x", request.path());
        assertEquals("application/json", request.headers().get("Content-Type"));
    }

    @Test
    void verbHelperMatchesTheDerivedDefault() {
        assertEquals(new CloudRequest("POST", "/x", null, null).idempotent(),
            CloudRequest.idempotentVerb("post"));
        assertEquals(new CloudRequest("PUT", "/x", null, null).idempotent(),
            CloudRequest.idempotentVerb("put"));
        assertFalse(CloudRequest.idempotentVerb(null), "null verb is conservative");
    }
}
