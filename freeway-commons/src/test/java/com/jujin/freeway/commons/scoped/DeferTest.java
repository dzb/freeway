package com.jujin.freeway.commons.scoped;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
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

    @Test
    void deferredActionThrowingErrorDoesNotBlockOthers() {
        // Errors too: deferred cleanup must always run to completion.
        List<String> log = new ArrayList<>();

        assertDoesNotThrow(() -> {
            Defer.within(() -> {
                Defer.defer(() -> log.add("before"));
                Defer.defer(() -> { throw new AssertionError("boom"); });
                Defer.defer(() -> log.add("after"));
            });
        });

        assertEquals(List.of("before", "after"), log,
            "an Error from one deferred action must not skip the remaining ones");
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
    void supplyGetInsideScopeDoesNotDoubleExecute() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Supplier<String> s = Defer.supply(() -> {
                log.add("computed");
                return "ok";
            });
            // get() inside scope computes immediately (not blocking)
            assertEquals("ok", s.get());
            assertEquals(List.of("computed"), log);
        });
        // drain should NOT re-execute since get() already computed it
        assertEquals(List.of("computed"), log, "should only compute once, not twice");
    }

    @Test
    void supplyGetInsideScopeCachesForDrain() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Supplier<String> s = Defer.supply(() -> {
                log.add("computed");
                return "ok";
            });
            // get() before commit — computes and caches
            s.get();
            // second get() returns cached
            s.get();
            assertEquals(1, log.size(), "get() should cache, not re-compute");
        });
        // drain — compute() should see computed==true and skip
        assertEquals(1, log.size(), "drain should not re-run after get() resolved it");
    }

    @Test
    void supplyFailureDoesNotDoubleExecute() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            Supplier<String> s = Defer.supply(() -> {
                log.add("run");
                throw new RuntimeException("fail");
            });
            // get() throws — but should mark computed so drain doesn't retry
            try { s.get(); } catch (RuntimeException ignored) {}
            assertEquals(List.of("run"), log);
        });
        // drain should NOT re-execute since get() already attempted
        assertEquals(List.of("run"), log, "should execute only once, not twice");
    }

    @Test
    void missingDependenciesSkipConstraint() {
        List<String> log = new ArrayList<>();
        Defer.within(() -> {
            Defer.defer("real", () -> log.add("real"));
            Defer.defer("orphan", () -> log.add("orphan")).after("nonexistent");
        });
        // orphan should still run, not be stuck waiting for missing dependency
        assertEquals(List.of("real", "orphan"), log);
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

    @Test
    void namedSupplyOutsideScopeRunsImmediatelyAndExposesValue() {
        List<String> log = new ArrayList<>();

        DeferAction handle = Defer.supply("x", () -> {
            log.add("computed");
            return "ok";
        });

        assertEquals(List.of("computed"), log,
            "named supply must run immediately outside a scope");
        assertEquals("ok", handle.value());
        assertEquals("ok", handle.value(), "value must be cached");
        assertEquals(1, log.size(), "value() must not recompute");
    }

    @Test
    void namedSupplyInsideScopeComputesAtCommitAndExposesValue() {
        List<String> log = new ArrayList<>();
        DeferAction[] holder = new DeferAction[1];

        Defer.within(() -> {
            holder[0] = Defer.supply("x", () -> {
                log.add("computed");
                return "ok";
            });
            assertTrue(log.isEmpty(), "must not compute inside the scope");
        });

        assertEquals(List.of("computed"), log);
        assertEquals("ok", holder[0].value());
    }

    @Test
    void namedSupplyValueBeforeCommitComputesImmediately() {
        List<String> log = new ArrayList<>();

        Defer.within(() -> {
            DeferAction handle = Defer.supply("x", () -> {
                log.add("computed");
                return "ok";
            });
            assertEquals("ok", handle.value());
            assertEquals(List.of("computed"), log);
        });

        assertEquals(List.of("computed"), log,
            "drain must not recompute a value already resolved");
    }

    @Test
    void plainDeferHandleHasNoValue() {
        Defer.within(() -> {
            DeferAction handle = Defer.defer("x", () -> {});
            assertNull(handle.value(),
                "plain defer() handles must not expose a value");
        });
    }

    // ====================== regression fixes ======================

    @Test
    void noopHandleIgnoresOrderingConstraints() {
        // Outside a scope, defer(id, ...) returns the shared NOOP singleton;
        // ordering constraints on it are meaningless and must not mutate the
        // shared state (before/after sets would cross-contaminate callers).
        DeferAction noop = Defer.defer("x", () -> {});
        noop.before("a").after("b");
        noop.before("c");
        assertTrue(noop.before().isEmpty(),
            "NOOP must not accumulate before() constraints");
        assertTrue(noop.after().isEmpty(),
            "NOOP must not accumulate after() constraints");
    }

    @Test
    void concurrentSupplierGetComputesOnce() throws Exception {
        // Regression guard for the AGENTS invariant "deferred suppliers
        // synchronized against duplicate computation": concurrent get() calls
        // on the same in-scope supplier must compute exactly once.
        AtomicInteger computations = new AtomicInteger();
        AtomicReference<Supplier<String>> holder = new AtomicReference<>();
        Defer.within(() -> holder.set(
            Defer.supply(() -> {
                computations.incrementAndGet();
                return "value";
            })
        ));
        Supplier<String> supplier = holder.get();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(supplier::get));
            }
            for (Future<String> f : futures) {
                assertEquals("value", f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, computations.get(),
            "concurrent get() must compute the deferred value exactly once");
    }

    @Test
    void deferFromSpawnedThreadRunsImmediately() throws Exception {
        // ScopedValue does not propagate to child threads: a defer() issued
        // from a spawned thread inside a scope must run immediately (the
        // thread sees no active scope), never buffer into the scope's drain.
        AtomicInteger runs = new AtomicInteger();
        Defer.within(() -> {
            try {
                Thread.ofVirtual()
                    .start(() -> Defer.defer(runs::incrementAndGet))
                    .join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertEquals(1, runs.get(),
            "cross-thread defer() must run immediately, not at drain");
    }

    @Test
    void failedSupplyRethrowsOnEveryAccess() {
        // Regression: a failed in-scope supply used to degrade to a silent
        // null forever (computed=true in finally); it must cache the failure
        // and rethrow it on every access instead.
        AtomicReference<Supplier<String>> holder = new AtomicReference<>();
        Defer.within(() -> holder.set(
            Defer.supply(() -> {
                throw new IllegalStateException("boom");
            })
        ));
        Supplier<String> supplier = holder.get();
        RuntimeException first = assertThrows(RuntimeException.class, supplier::get);
        assertTrue(first.getCause() != null
            && "boom".equals(first.getCause().getMessage()));
        RuntimeException second = assertThrows(RuntimeException.class, supplier::get);
        assertSame(first, second,
            "the cached failure must be rethrown, never a silent null");
    }

}

