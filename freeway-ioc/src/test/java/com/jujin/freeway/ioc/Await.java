package com.jujin.freeway.ioc;

import java.util.function.BooleanSupplier;

/**
 * Polling helper for async assertions in bus tests: shared single-source
 * timing policy instead of per-file copies.
 */
final class Await {

    private Await() {}

    /** Polls {@code condition} every 10 ms until true or {@code timeoutMs}. */
    static void until(long timeoutMs, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError(
                    "Condition not met within " + timeoutMs + " ms");
            }
            Thread.sleep(10);
        }
    }
}
