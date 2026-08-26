package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.AppConfigDefault;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HookLifecycle} contracts: ordered start, reverse-order stop,
 * rollback on failed start (including the failing hook itself), Error
 * pass-through, and suppression-chained stop failures.
 */
class HookLifecycleTest {

    /** Records lifecycle transitions; can fail start or stop on demand. */
    private static final class RecordingHook implements RuntimeHook {
        private final String name;
        private final List<String> log;
        private final RuntimeException startFailure;
        private final Error startError;
        private final RuntimeException stopFailure;

        RecordingHook(String name, List<String> log) {
            this(name, log, null, null, null);
        }

        RecordingHook(String name, List<String> log, RuntimeException startFailure,
                      Error startError, RuntimeException stopFailure) {
            this.name = name;
            this.log = log;
            this.startFailure = startFailure;
            this.startError = startError;
            this.stopFailure = stopFailure;
        }

        @Override
        public void start(Container container) {
            log.add(name + ":start");
            if (startError != null) {
                throw startError;
            }
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void stop(Container container) {
            log.add(name + ":stop");
            if (stopFailure != null) {
                throw stopFailure;
            }
        }
    }

    private static Container containerWith(RuntimeHook... hooks) {
        AppConfig config = new AppConfigDefault(Map.of(), List.of());
        ModuleEx hooksModule = new ModuleEx() {
            @Override
            public void bind(com.jujin.freeway.ioc.Binder binder) {
                var contributions = binder.contribute(RuntimeHook.class);
                for (int i = 0; i < hooks.length; i++) {
                    contributions.add("hook" + i, hooks[i]);
                }
            }
        };
        return Freeway.create(new BootConfigModule(config), hooksModule);
    }

    @Test
    void startsInDeclaredOrderStopsInReverse() {
        List<String> log = new ArrayList<>();
        HookLifecycle lifecycle = containerWith(
            new RecordingHook("a", log),
            new RecordingHook("b", log),
            new RecordingHook("c", log)
        ).get(HookLifecycle.class);

        lifecycle.start();
        lifecycle.stop();
        assertEquals(List.of("a:start", "b:start", "c:start", "c:stop", "b:stop", "a:stop"), log);
    }

    @Test
    void failedStartStopsFailingHookThenRollsBackStartedOnes() {
        List<String> log = new ArrayList<>();
        RuntimeException cause = new RuntimeException("boot failure");
        HookLifecycle lifecycle = containerWith(
            new RecordingHook("a", log),
            new RecordingHook("b", log, cause, null, null),
            new RecordingHook("c", log)
        ).get(HookLifecycle.class);

        RuntimeException ex = assertThrows(RuntimeException.class, lifecycle::start);
        assertEquals("Runtime hook start failed", ex.getMessage());
        assertSame(cause, ex.getCause());
        // The failing hook gets a chance to release resources, then started
        // ones roll back in reverse; c never starts and is never stopped.
        assertEquals(List.of("a:start", "b:start", "b:stop", "a:stop"), log);
    }

    @Test
    void errorFromStartPropagatesUnwrappedAfterRollback() {
        List<String> log = new ArrayList<>();
        AssertionError error = new AssertionError("fatal");
        HookLifecycle lifecycle = containerWith(
            new RecordingHook("a", log, null, error, null)
        ).get(HookLifecycle.class);

        AssertionError thrown = assertThrows(AssertionError.class, lifecycle::start);
        assertSame(error, thrown);
        assertEquals(List.of("a:start", "a:stop"), log);
    }

    @Test
    void stopFailuresAccumulateIntoOneSuppressionChain() {
        List<String> log = new ArrayList<>();
        HookLifecycle lifecycle = containerWith(
            new RecordingHook("a", log, null, null, new RuntimeException("a-stop")),
            new RecordingHook("c", log, null, null, new RuntimeException("c-stop"))
        ).get(HookLifecycle.class);

        lifecycle.start();
        RuntimeException failure = assertThrows(RuntimeException.class, lifecycle::stop);
        assertEquals("Runtime hook stop failed", failure.getMessage());
        // Stop runs in reverse: c fails first (becomes the primary failure),
        // a's failure is chained on as suppressed. Each link wraps its
        // original exception as the cause.
        assertEquals("c-stop", failure.getCause().getMessage());
        assertEquals(1, failure.getSuppressed().length,
            "the second stop failure is suppressed onto the first");
        assertEquals("a-stop", failure.getSuppressed()[0].getCause().getMessage());
        // Both hooks were still visited despite their stop failures.
        assertEquals(List.of("a:start", "c:start", "c:stop", "a:stop"), log);
    }

    @Test
    void repeatedStartIsNoOpAndStopWithoutStartIsEmpty() {
        List<String> log = new ArrayList<>();
        HookLifecycle lifecycle = containerWith(new RecordingHook("a", log))
            .get(HookLifecycle.class);

        lifecycle.start();
        assertDoesNotThrow(lifecycle::start);
        lifecycle.stop();
        assertDoesNotThrow(lifecycle::stop);
        assertEquals(List.of("a:start", "a:stop"), log);
    }
}
