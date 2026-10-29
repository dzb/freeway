package com.jujin.freeway.commons.scoped;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void getInsideScopeCachesNullValues() {
        AtomicInteger count = new AtomicInteger();
        ScopedCache.within(() -> {
            assertNull(ScopedCache.get("k", () -> {
                count.incrementAndGet();
                return null;
            }));
            assertNull(ScopedCache.get("k", () -> {
                count.incrementAndGet();
                return null;
            }));
            assertEquals(1, count.get());
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
            ScopeSession s = ScopedCache.currentSession();
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
            scope.closeValues();
            scope.closeValues();
            assertEquals(1, cleaned.get());
            return null;
        });
    }

    @Test
    void getAfterCloseThrows() {
        ScopedCache.within(scope -> {
            scope.closeValues();
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
    void onCloseHandlerErrorDoesNotBlockOthers() {
        AtomicInteger secondFired = new AtomicInteger();
        ScopedCache.onClose(v -> { throw new Error("fail"); });
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

    @Test
    void nullCacheValueDoesNotBreakOnClose() {
        ScopedCache.onClose(v -> {});
        ScopedCache.within(() -> {
            ScopedCache.get("nullVal", () -> null);
        });
        // should not throw NPE
    }

    // ==================== entry cleanup ====================

    @Test
    void entryCleanupRunsOnceOnScopeExit() {
        AtomicInteger cleaned = new AtomicInteger();
        Object[] seen = new Object[1];
        ScopedCache.within(() -> {
            String value = ScopedCache.get("k", () -> "v", v -> {
                cleaned.incrementAndGet();
                seen[0] = v;
            });
            assertEquals("v", value);
            assertEquals(0, cleaned.get());
            return null;
        });

        assertEquals(1, cleaned.get());
        assertEquals("v", seen[0]);
    }

    @Test
    void entryCleanupSkippedOutsideScope() {
        AtomicInteger cleaned = new AtomicInteger();
        Object value = ScopedCache.get("k", () -> "v", v -> cleaned.incrementAndGet());

        assertEquals("v", value);
        assertEquals(0, cleaned.get(),
            "outside a scope the caller owns lifecycle: the cleanup never runs");
    }

    @Test
    void sharedValueRunsFirstCleanupOnce() {
        // First registration wins: one instance under two keys runs only the
        // first key's cleanup — the entry-level mirror of the remove()-once
        // membership claim the container side replaces.
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        Object shared = new Object();
        ScopedCache.within(() -> {
            ScopedCache.get("a", () -> shared, v -> first.incrementAndGet());
            ScopedCache.get("b", () -> shared, v -> second.incrementAndGet());
            return null;
        });

        assertEquals(1, first.get());
        assertEquals(0, second.get());
    }

    // ====================== Defer / cache convergence ======================

    @Test
    void cacheOpenedInsideActionScopeAttachesAndOutlivesTheInnerBlock() {
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicInteger cleaned = new AtomicInteger();
        Defer.within(() -> {
            ScopedCache.within(() -> {
                ScopedCache.get("k", () -> "v", v -> cleaned.incrementAndGet());
                Defer.defer(() -> seen.set(ScopedCache.get("k", () -> "recreated")));
            });
            // Access ends with the block; the value still belongs to the action scope.
            assertFalse(ScopedCache.isActive(), "value access must end with the cache block");
            assertEquals("recreated",
                ScopedCache.get("k", () -> "recreated"),
                "outside the window get creates uncached instead of reading the action scope");
            assertEquals(0, cleaned.get(), "cleanup must wait for the action scope");
        });
        assertEquals("v", seen.get(),
            "a deferred action must still see the value cached in the inner block");
        assertEquals(1, cleaned.get(), "the value must be cleaned exactly once");
        assertFalse(ScopedCache.isActive());
    }

    @Test
    void actionScopeInsideCacheKeepsTheOuterValuesOpen() {
        AtomicReference<String> seen = new AtomicReference<>();
        ScopedCache.within(() -> {
            ScopedCache.get("k", () -> "v");
            Defer.within(() ->
                Defer.defer(() -> seen.set(ScopedCache.get("k", () -> "recreated"))));
        });
        assertEquals("v", seen.get(),
            "an action scope may read values owned by an enclosing cache scope");
    }
}
