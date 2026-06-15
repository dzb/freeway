package com.jujin.freeway.commons.scoped;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScopedCacheTest {

    @AfterEach
    void resetCleanup() {
        ScopedCache.resetCleanups();
    }

    // ==================== basic ====================

    @Test
    void getOutsideScopeRunsImmediately() {
        AtomicInteger count = new AtomicInteger();
        ScopedCache.get("k", count::incrementAndGet);
        assertEquals(1, count.get());
    }

    @Test
    void getOutsideScopeNoCaching() {
        AtomicInteger count = new AtomicInteger();
        ScopedCache.get("k", count::incrementAndGet);
        ScopedCache.get("k", count::incrementAndGet);
        assertEquals(2, count.get());
    }

    @Test
    void isActiveOutsideScopeReturnsFalse() {
        assertFalse(ScopedCache.isActive());
    }

    @Test
    void currentSessionOutsideReturnsNull() {
        assertSame(null, ScopedCache.currentSession());
    }

    // ==================== within ====================

    @Test
    void getInsideScopeCaches() {
        AtomicInteger count = new AtomicInteger();
        ScopedCache.within(() -> {
            Object first = ScopedCache.get("k", count::incrementAndGet);
            Object second = ScopedCache.get("k", count::incrementAndGet);
            assertSame(first, second);
            assertEquals(1, count.get());
            return null;
        });
    }

    @Test
    void getInsideScopeDifferentKeys() {
        AtomicInteger count = new AtomicInteger();
        ScopedCache.within(() -> {
            Object a = ScopedCache.get("a", count::incrementAndGet);
            Object b = ScopedCache.get("b", count::incrementAndGet);
            assertNotSame(a, b);
            assertEquals(2, count.get());
            return null;
        });
    }

    @Test
    void isActiveInsideScopeReturnsTrue() {
        ScopedCache.within(() -> {
            assertTrue(ScopedCache.isActive());
            return null;
        });
        assertFalse(ScopedCache.isActive());
    }

    @Test
    void currentSessionReturnsScopeInside() {
        ScopedCache.within(() -> {
            ScopedCache.Session s = ScopedCache.currentSession();
            assertSame(s, ScopedCache.currentSession());
            return null;
        });
    }

    @Test
    void withinRunnableExecutes() {
        AtomicInteger count = new AtomicInteger();
        ScopedCache.within(() -> count.incrementAndGet());
        assertEquals(1, count.get());
    }

    @Test
    void withinSupplierReturnsValue() {
        int result = ScopedCache.within(() -> 42);
        assertEquals(42, result);
    }

    @Test
    void withinFunctionReturnsValue() {
        int result = ScopedCache.within(scope -> {
            assertSame(scope, ScopedCache.currentSession());
            return 99;
        });
        assertEquals(99, result);
    }

    @Test
    void withinClosesOnException() {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger cleaned = new AtomicInteger();

        ScopedCache.onClose(v -> cleaned.incrementAndGet());

        assertThrows(RuntimeException.class, () ->
            ScopedCache.within(() -> {
                ScopedCache.get("k", created::incrementAndGet);
                throw new RuntimeException("boom");
            })
        );

        assertEquals(1, created.get());
        assertEquals(1, cleaned.get());
    }

    // ==================== nested ====================

    @Test
    void nestedScopesAreIndependent() {
        AtomicInteger count = new AtomicInteger();
        ScopedCache.within(() -> {
            Object outer = ScopedCache.get("k", count::incrementAndGet);
            assertEquals(1, count.get());

            ScopedCache.within(() -> {
                Object inner = ScopedCache.get("k", count::incrementAndGet);
                assertNotSame(outer, inner);
                assertEquals(2, count.get());
                return null;
            });

            // Outer cache still intact
            Object stillOuter = ScopedCache.get("k", () -> { throw new AssertionError(); });
            assertSame(outer, stillOuter);
            return null;
        });
    }

    @Test
    void nestedScopesCloseIndependently() {
        AtomicInteger cleaned = new AtomicInteger();
        ScopedCache.onClose(v -> cleaned.incrementAndGet());

        ScopedCache.within(() -> {
            ScopedCache.get("outer", () -> "outer");
            assertEquals(0, cleaned.get());

            ScopedCache.within(() -> {
                ScopedCache.get("inner", () -> "inner");
                assertEquals(0, cleaned.get());
                return null;
            });

            // Inner closed
            assertEquals(1, cleaned.get());
            return null;
        });

        // Both closed
        assertEquals(2, cleaned.get());
    }

    // ==================== close ====================

    @Test
    void closeIsIdempotent() {
        AtomicInteger cleaned = new AtomicInteger();
        ScopedCache.onClose(v -> cleaned.incrementAndGet());

        ScopedCache.within(scope -> {
            ScopedCache.get("k", () -> "v");
            scope.close();
            scope.close();
            assertEquals(1, cleaned.get());
            return null;
        });
    }

    @Test
    void getAfterCloseThrows() {
        ScopedCache.within(scope -> {
            scope.close();
            assertThrows(IllegalStateException.class, () ->
                ScopedCache.get("k", () -> "v")
            );
            return null;
        });
    }

    @Test
    void onCloseHandlerExceptionDoesNotBlockOthers() {
        AtomicInteger secondFired = new AtomicInteger();
        ScopedCache.onClose(v -> { throw new RuntimeException("fail"); });
        ScopedCache.onClose(v -> secondFired.incrementAndGet());

        ScopedCache.within(() -> {
            ScopedCache.get("k", () -> "v");
            return null;
        });

        assertEquals(1, secondFired.get());
    }

    @Test
    void identityDedupOnClose() {
        AtomicInteger cleaned = new AtomicInteger();
        ScopedCache.onClose(v -> cleaned.incrementAndGet());

        // Same instance under two different keys
        Object shared = "shared";
        ScopedCache.within(() -> {
            ScopedCache.get("a", () -> shared);
            ScopedCache.get("b", () -> shared);
            return null;
        });

        // Cleaned only once — identity dedup
        assertEquals(1, cleaned.get());
    }

    // ==================== multiple handlers ====================

    @Test
    void multipleOnCloseHandlersFire() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        ScopedCache.onClose(v -> first.incrementAndGet());
        ScopedCache.onClose(v -> second.incrementAndGet());

        ScopedCache.within(() -> {
            ScopedCache.get("k", () -> "v");
            return null;
        });

        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void onCloseNotCalledWhenNoValuesCached() {
        AtomicInteger cleaned = new AtomicInteger();
        ScopedCache.onClose(v -> cleaned.incrementAndGet());

        ScopedCache.within(() -> null);

        assertEquals(0, cleaned.get());
    }
}
