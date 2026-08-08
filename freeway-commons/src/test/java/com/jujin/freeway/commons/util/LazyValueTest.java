package com.jujin.freeway.commons.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LazyTest {

    @Test
    void computesExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<String> lazy = LazyValue.of(() -> "v" + calls.incrementAndGet());

        assertFalse(lazy.isComputed());
        assertNull(lazy.peek());
        assertEquals("v1", lazy.get());
        assertTrue(lazy.isComputed());
        assertEquals("v1", lazy.get());
        assertEquals("v1", lazy.get());
        assertEquals(1, calls.get(), "supplier must run exactly once");
    }

    @Test
    void concurrentReadersShareOneComputation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<String> lazy = LazyValue.of(() -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "shared";
        });
        var results = java.util.concurrent.Executors.newFixedThreadPool(8)
            .invokeAll(java.util.Collections.nCopies(8, lazy::get))
            .stream()
            .map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            })
            .toList();
        assertEquals(1, calls.get(), "8 concurrent readers must trigger one computation");
        assertEquals(8, results.stream().filter("shared"::equals).count());
    }

    @Test
    void throwingSupplierRetriesOnNextCall() {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<String> lazy = LazyValue.of(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("first attempt fails");
            }
            return "recovered";
        });
        assertThrows(IllegalStateException.class, lazy::get);
        assertEquals("recovered", lazy.get(), "failure must not be cached — next call retries");
    }

    @Test
    void nullSupplierResultRejected() {
        LazyValue<String> lazy = LazyValue.of(() -> null);
        assertThrows(NullPointerException.class, lazy::get,
            "a null supplier result is a contract violation — it would "
                + "otherwise re-compute forever");
    }
}
