package com.jujin.freeway.cloud.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A context carrying nothing is no context: the carrier type decides that, not each caller. Both
 * the structured binding and the ambient fallback must treat an all-unset context as absent —
 * otherwise the same publish would observe a present-but-empty context when it runs on the
 * submitting thread and none when it is dispatched asynchronously.
 */
class InvocationContextTest {

    private static InvocationContext blank() {
        return InvocationContext.of(null, null, null);
    }

    @Test
    void blankContextBindsNothing() {
        InvocationContext.runWith(blank(), () ->
            assertTrue(InvocationContext.current().isEmpty(),
                "an all-unset context must not be bound — running bare and binding nothing are the same"));
    }

    @Test
    void aCarryingContextStillBinds() {
        var baggage = Baggage.of(java.util.Map.of("tenant", "acme"));

        InvocationContext.runWith(InvocationContext.of(null, null, baggage), () -> {
            assertFalse(InvocationContext.current().isEmpty(), "one sub-context is enough to bind");
            assertTrue(InvocationContext.current().get().baggage() == baggage);
        });
    }

    @Test
    void blankAmbientClearsInsteadOfInstalling() {
        InvocationContext previous = InvocationContext.replaceAmbient(blank());
        try {
            assertTrue(InvocationContext.current().isEmpty(),
                "replaceAmbient(blank) must clear, not install an empty context");
        } finally {
            InvocationContext.replaceAmbient(previous);
        }
    }

    @Test
    void carriesNothingTracksEverySubContext() {
        assertTrue(blank().carriesNothing());
        assertFalse(InvocationContext.of(null, null, Baggage.of(java.util.Map.of())).carriesNothing(),
            "a present (even empty) baggage is still baggage");
    }
}
