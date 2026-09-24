package com.jujin.freeway.cloud.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The self-guard truth table: only equal non-null identities match. */
class EventOriginTest {

    @Test
    void equalIdentitiesAreOwn() {
        assertTrue(EventOrigin.isOwn("node-1", "node-1"));
    }

    @Test
    void differentIdentitiesAreForeign() {
        assertFalse(EventOrigin.isOwn("node-1", "node-2"));
    }

    @Test
    void unknownIdentityIsNeverOwn() {
        // A missing origin header must not match — least surprise when the
        // other side predates origin stamping, and no NPE on either side.
        assertFalse(EventOrigin.isOwn("node-1", null));
        assertFalse(EventOrigin.isOwn(null, null));
        assertFalse(EventOrigin.isOwn(null, "node-1"));
    }
}
