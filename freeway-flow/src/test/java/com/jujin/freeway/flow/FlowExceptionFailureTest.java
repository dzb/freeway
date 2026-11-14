package com.jujin.freeway.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.error.Failure;
import org.junit.jupiter.api.Test;

/** Flow execution failures are conservative: side effects of a failed task may have landed. */
class FlowExceptionFailureTest {

    @Test
    void flowFailureUsesTheConservativeDefaults() {
        Failure failure = new FlowException("boom");
        assertFalse(failure.retryable());
        assertTrue(failure.outcomeUnknown());
    }
}
