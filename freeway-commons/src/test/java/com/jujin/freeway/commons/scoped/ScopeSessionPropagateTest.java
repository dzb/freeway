package com.jujin.freeway.commons.scoped;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link ScopeSession#propagate} makes a scope's binding reachable from another
 * thread: the advice {@code ScopeFilter} gives ("async work must use
 * {@code ContextExecutor}") becomes followable, and a deferred action registered
 * on the worker joins the submitting scope.
 */
class ScopeSessionPropagateTest {

    private static final ScopedValue<String> KEY = ScopedValue.newInstance();

    @Test
    void deferredActionFromWorkerJoinsTheSubmittingScope() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Executor propagated = ScopeSession.propagate(pool);
            AtomicBoolean workerSawScope = new AtomicBoolean();
            AtomicBoolean actionRan = new AtomicBoolean();
            CountDownLatch submitted = new CountDownLatch(1);

            Defer.within(() -> {
                propagated.execute(() -> {
                    workerSawScope.set(Defer.isActive());
                    Defer.defer(() -> actionRan.set(true));
                    submitted.countDown();
                });
                awaitUnchecked(submitted);
            });

            assertTrue(workerSawScope.get(), "the worker must see the submitting Defer scope");
            assertTrue(actionRan.get(), "an action deferred on the worker runs with the scope");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void outsideAnyScopeNothingIsPropagated() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Executor propagated = ScopeSession.propagate(pool);
            AtomicBoolean workerSawScope = new AtomicBoolean(true);
            CountDownLatch done = new CountDownLatch(1);

            propagated.execute(() -> {
                workerSawScope.set(Defer.isActive());
                done.countDown();
            });
            await(done);

            assertFalse(workerSawScope.get(), "no submitting scope means no propagated scope");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void extraKeysRideAlongWithTheScope() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Executor propagated = ScopeSession.propagate(pool, KEY);
            AtomicReference<String> seen = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);

            ScopedValue.where(KEY, "frame-1").run(() -> {
                propagated.execute(() -> {
                    seen.set(KEY.isBound() ? KEY.get() : null);
                    done.countDown();
                });
                awaitUnchecked(done);
            });

            assertEquals("frame-1", seen.get(), "extra keys must reach the worker");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void unboundExtraKeyStaysUnbound() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Executor propagated = ScopeSession.propagate(pool, KEY);
            AtomicReference<String> seen = new AtomicReference<>("not-run");
            CountDownLatch done = new CountDownLatch(1);

            propagated.execute(() -> {
                seen.set(KEY.isBound() ? KEY.get() : null);
                done.countDown();
            });
            await(done);

            assertNull(seen.get(), "an unbound extra key must not be bound on the worker");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "worker did not finish in time");
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            await(latch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the worker", e);
        }
    }
}
