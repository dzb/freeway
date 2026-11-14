package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.error.Failure;
import com.jujin.freeway.cloud.storage.StorageException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * The shared classification view carries RPC's precise wire semantics; the
 * other domain failures stay conservative.
 */
class RpcExceptionFailureTest {

    @Test
    void connectFailureIsRetryableWithAKnownOutcome() {
        Failure failure = RpcException.connect("svc", new IOException("refused"));
        assertTrue(failure.retryable());
        assertFalse(failure.outcomeUnknown(), "the request never left this process");
    }

    @Test
    void timeoutIsRetryableButAmbiguous() {
        Failure failure = RpcException.timeout("svc");
        assertTrue(failure.retryable());
        assertTrue(failure.outcomeUnknown());
    }

    @Test
    void localRejectionIsNotRetryable() {
        Failure failure = RpcException.noInstance("svc");
        assertFalse(failure.retryable());
        assertFalse(failure.outcomeUnknown());
    }

    @Test
    void storageFailureUsesTheConservativeDefaults() {
        Failure failure = new StorageException("boom");
        assertFalse(failure.retryable());
        assertTrue(failure.outcomeUnknown());
    }
}
