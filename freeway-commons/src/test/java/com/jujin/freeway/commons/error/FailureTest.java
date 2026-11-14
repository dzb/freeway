package com.jujin.freeway.commons.error;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The default classification is deliberately conservative. */
class FailureTest {

    @Test
    void unclassifiedFailureIsNeverRetriedAndItsOutcomeIsUnknown() {
        Failure failure = new Failure() { };
        assertFalse(failure.retryable(), "no stated class means no automatic retry");
        assertTrue(failure.outcomeUnknown(), "no stated guarantee means assume side effects landed");
    }
}
