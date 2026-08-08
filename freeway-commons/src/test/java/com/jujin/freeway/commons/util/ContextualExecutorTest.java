package com.jujin.freeway.commons.util;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContextualExecutorTest {

    private static final ScopedValue<String> CTX = ScopedValue.newInstance();

    @Test
    void taskRunsWithSubmittingThreadSnapshot() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var executor = ContextualExecutor.wrapping(pool, CTX);
            AtomicReference<String> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            ScopedValue.where(CTX, "tx-123").run(() -> {
                executor.execute(() -> {
                    seen.set(CTX.get());
                    done.countDown();
                });
            });

            assertTrue(done.await(5, TimeUnit.SECONDS), "task must run");
            assertEquals("tx-123", seen.get(),
                "the ScopedValue binding must reach the worker thread");
        } finally {
            pool.close();
        }
    }

    @Test
    void plainExecutorDoesNotPropagateBindings() throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            AtomicReference<String> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            ScopedValue.where(CTX, "tx-123").run(() -> {
                pool.execute(() -> {
                    try {
                        seen.set(CTX.get());
                    } catch (NoSuchElementException e) {
                        seen.set(null); // no binding on the worker
                    }
                    done.countDown();
                });
            });

            assertTrue(done.await(5, TimeUnit.SECONDS), "task must run");
            assertNull(seen.get(),
                "a plain executor sees no bindings — propagation is opt-in");
        } finally {
            pool.close();
        }
    }
}
