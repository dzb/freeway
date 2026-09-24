package com.jujin.freeway.ioc.event;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * White-box tests for the bus's executor support — lives beside it in the
 * event package because {@link EventExecutorSupport} is package-private.
 */
class EventExecutorSupportTest {

    @Test
    void closeDoesNotHangOnABlockedExecutorTask() throws Exception {
        // Regression: close() used ExecutorService.close() — an unbounded
        // wait. One hung subscriber task would hang the whole container's
        // shutdown (EventBus closes last, after every @PreDestroy). The wait
        // must be bounded, then force the executor down.
        EventExecutorSupport support = new EventExecutorSupport(() -> { }, 100);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch blockForever = new CountDownLatch(1);
        support.orderedExecutor().submit(() -> {
            started.countDown();
            try {
                blockForever.await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(started.await(2, TimeUnit.SECONDS),
            "the ordered task must be running before close()");

        long start = System.nanoTime();
        support.close();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 5_000,
            "close() must give up after the bounded wait, not hang; took "
                + elapsedMs + " ms");
        blockForever.countDown(); // release the interrupted worker
    }
}
