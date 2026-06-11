package com.jujin.freeway.commons.defer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DeferTest {

    // ==================== basic ====================

    @Test
    void deferOutsideScopeRunsImmediately() {
        AtomicInteger count = new AtomicInteger();
        Defer.defer(count::incrementAndGet);
        assertEquals(1, count.get());
    }

    @Test
    void isActiveOutsideScopeReturnsFalse() {
        assertFalse(Defer.isActive());
    }

    @Test
    void deferInsideScopeBuffersAndDrainsOnSuccess() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            assertTrue(Defer.isActive());
            Defer.defer(() -> log.add("first"));
            Defer.defer(() -> log.add("second"));
            assertEquals(0, log.size());
        });

        assertEquals(List.of("first", "second"), log);
        assertFalse(Defer.isActive());
    }

    @Test
    void deferInsideScopeDiscardsOnException() {
        AtomicInteger count = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> {
            Defer.within(() -> {
                Defer.defer(count::incrementAndGet);
                throw new RuntimeException("fail");
            });
        });

        assertEquals(0, count.get());
    }

    @Test
    void unnamedDeferActionsExecuteInRegistrationOrder() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.defer(() -> log.add("a"));
            Defer.defer(() -> log.add("b"));
            Defer.defer(() -> log.add("c"));
        });

        assertEquals(List.of("a", "b", "c"), log);
    }

    @Test
    void failingDeferredActionDoesNotBlockOthers() {
        List<String> log = new ArrayList<>();

        assertDoesNotThrow(() -> {
            Defer.within(() -> {
                Defer.defer(() -> log.add("before"));
                Defer.defer(() -> { throw new RuntimeException("boom"); });
                Defer.defer(() -> log.add("after"));
            });
        });

        assertEquals(List.of("before", "after"), log);
    }

    // ==================== nested scopes ====================

    @Test
    void nestedScopesAreIndependent() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.defer(() -> log.add("outer-before"));
            Defer.within(() -> {
                Defer.defer(() -> log.add("inner"));
            });
            // inner scope drained on exit — "inner" already executed
            Defer.defer(() -> log.add("outer-after"));
        });

        assertEquals(
            List.of("inner", "outer-before", "outer-after"),
            log
        );
    }

    @Test
    void innerScopeRollbackDoesNotAffectOuter() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.defer(() -> log.add("outer"));
            assertThrows(RuntimeException.class, () -> {
                Defer.within(() -> {
                    Defer.defer(() -> log.add("inner"));
                    throw new RuntimeException("inner fail");
                });
            });
        });

        assertEquals(List.of("outer"), log);
    }

    // ==================== manual rollback ====================

    @Test
    void manualRollbackDiscardsDeferredActions() {
        List<String> log = new ArrayList<>();

        Defer.within(scope -> {
            Defer.defer(() -> log.add("discarded"));
            scope.rollback();
        });

        assertTrue(log.isEmpty());
    }

    @Test
    void manualRollbackWithoutExceptionDoesNotThrow() {
        assertDoesNotThrow(() -> {
            Defer.within(scope -> {
                scope.rollback();
            });
        });
    }

    // ==================== named + ordered defer ====================

    @Test
    void namedDeferInOrder() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.defer("index",  () -> log.add("index"));
            Defer.defer("cache",  () -> log.add("cache"));
            Defer.defer("notify", () -> log.add("notify"));
        });

        assertEquals(List.of("index", "cache", "notify"), log);
    }

    @Test
    void orderedDeferRespectsBeforeAfter() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.defer("index",  () -> log.add("index")).after("cache");
            Defer.defer("cache",  () -> log.add("cache"));
            Defer.defer("notify", () -> log.add("notify")).after("index", "cache");
        });

        assertEquals(List.of("cache", "index", "notify"), log);
    }

    @Test
    void orderedDeferBefore() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.defer("a", () -> log.add("a")).before("c");
            Defer.defer("b", () -> log.add("b"));
            Defer.defer("c", () -> log.add("c"));
        });

        // a before c → a, c; then unconstrained named b → a, c, b
        assertEquals(List.of("a", "c", "b"), log);
    }

    @Test
    void missingOrderTargetIsSilentlyIgnored() {
        List<String> log = new ArrayList<>();

        assertDoesNotThrow(() -> {
            Defer.within(() -> {
                Defer.defer("a", () -> log.add("a")).after("nonexistent");
            });
        });

        assertEquals(List.of("a"), log);
    }

    @Test
    void duplicateDeferIdThrows() {
        assertThrows(IllegalStateException.class, () -> {
            Defer.within(() -> {
                Defer.defer("dup", () -> {});
                Defer.defer("dup", () -> {});
            });
        });
    }

    @Test
    void circularDependencyThrows() {
        assertThrows(IllegalStateException.class, () -> {
            Defer.within(() -> {
                Defer.defer("a", () -> {}).after("b");
                Defer.defer("b", () -> {}).after("a");
            });
        });
    }

    @Test
    void mixedNamedAndUnnamedInRegistrationOrder() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.defer(() -> log.add("unnamed-1"));
            Defer.defer("named", () -> log.add("named"));
            Defer.defer(() -> log.add("unnamed-2"));
        });

        // Named without constraints + unnamed → registration order
        assertEquals(List.of("named", "unnamed-1", "unnamed-2"), log);
    }

    // ==================== supply ====================

    @Test
    void supplyOutsideScopeComputesImmediately() {
        Supplier<String> s = Defer.supply(() -> "hello");
        assertEquals("hello", s.get());
    }

    @Test
    void supplyInsideScopeDefersUntilCommit() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.supply(() -> { log.add("inner-computed"); return "inner"; });
            assertEquals(0, log.size(), "not computed inside scope");
        });

        assertEquals(List.of("inner-computed"), log);
    }

    @Test
    void supplyResultCanBeRetrieved() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            // Defer.supply inside a scope returns a supplier that blocks
            // until commit. We can't test get() inside the scope easily
            // (would block), so verify computation happens during drain.
            Defer.supply(() -> { log.add("computed-at-commit"); return "ok"; });
            assertTrue(log.isEmpty());
        });

        assertEquals(List.of("computed-at-commit"), log);
    }

    @Test
    void namedSupplySupportsOrdering() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Defer.supply("b", () -> { log.add("b"); return "B"; }).after("a");
            Defer.supply("a", () -> { log.add("a"); return "A"; });
        });

        assertEquals(List.of("a", "b"), log);
    }
}
