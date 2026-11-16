package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Call bounds are part of the client contract, never optional defaults. */
class RpcHttpClientContractTest {

    @Test
    void attemptAndDeadlineOverloadsMustBeImplementedByEveryClient() throws Exception {
        Method attempts = RpcHttpClient.class.getMethod(
            "call", String.class, RpcRequest.class, int.class);
        Method async = RpcHttpClient.class.getMethod(
            "callAsync", String.class, RpcRequest.class);
        Method deadline = RpcHttpClient.class.getMethod(
            "callAsync", String.class, RpcRequest.class, Duration.class);
        Method deadlineAndAttempts = RpcHttpClient.class.getMethod(
            "callAsync", String.class, RpcRequest.class, Duration.class, int.class);

        assertTrue(Modifier.isAbstract(attempts.getModifiers()),
            "maxAttempts must not silently delegate");
        assertTrue(Modifier.isAbstract(async.getModifiers()),
            "async behavior must be owned by the implementation");
        assertTrue(Modifier.isAbstract(deadline.getModifiers()),
            "deadline must not be silently discarded");
        assertTrue(Modifier.isAbstract(deadlineAndAttempts.getModifiers()),
            "deadline and maxAttempts must not be silently discarded");
    }
}
