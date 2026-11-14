package com.jujin.freeway.db;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.error.Failure;
import org.junit.jupiter.api.Test;

/** SQL failures are conservative: the framework never retries them, and their side effects are not visible here. */
class SqlExceptionFailureTest {

    @Test
    void sqlFailureUsesTheConservativeDefaults() {
        Failure failure = new SqlException("boom");
        assertFalse(failure.retryable());
        assertTrue(failure.outcomeUnknown());
    }
}
