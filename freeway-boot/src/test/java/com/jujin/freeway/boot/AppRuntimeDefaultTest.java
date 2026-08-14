package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfigModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.EventSubscriber;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle-boundary regressions for {@link AppRuntimeDefault}: reentrant
 * {@code close()} during {@code start()} and {@code Error} (non-RuntimeException)
 * failures during hook startup.
 */
class AppRuntimeDefaultTest {

    /** Build a standalone runtime for direct lifecycle observation. */
    private static AppRuntime runtime(ModuleEx module) {
        AppConfig config = new AppConfigDefault(Map.of(), List.of());
        Container container = Freeway.create(new BootConfigModule(config), module);
        return new AppRuntimeDefault(container, config);
    }

    @Test
    void closeDuringHookStartAbortsStartup() {
        var events = new CopyOnWriteArrayList<Object>();
        var module = new ReentrantCloseModule(events);
        AppRuntime app = runtime(module);
        module.runtime = app;

        // The hook calls close() (reentrant, same thread) while start() is
        // still running the hook phase. start() must not clobber the STOPPED
        // state set by the nested close(): it must not report RUNNING, must
        // not publish AppStartedEvent, and must not throw a state error.
        assertDoesNotThrow(app::start);

        assertFalse(app.isRunning());
        assertEquals(AppState.STOPPED, app.state());
        // The nested close() ran the full shutdown sequence, so the container
        // is closed: lookups fail with the closed-container error — not a
        // "cannot start from state" style state-machine error.
        IllegalStateException ex = assertThrows(
            IllegalStateException.class, () -> app.get(String.class));
        assertTrue(ex.getMessage().contains("Container is closed"),
            "got: " + ex.getMessage());
        assertTrue(events.stream().noneMatch(e -> e instanceof AppStartedEvent),
            "AppStartedEvent must not be published when shutdown happened during startup");
        assertEquals(1, events.stream()
                .filter(e -> e instanceof AppStoppingEvent).count(),
            "AppStoppingEvent must be published exactly once (by the reentrant close())");

        // Shutdown was already attempted — a later close() is a no-op.
        app.close();
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void errorDuringHookStartFailsAndRollsBack() {
        var events = new CopyOnWriteArrayList<String>();
        AppRuntime app = runtime(new ErrorHookModule(events));

        IllegalStateException ex = assertThrows(
            IllegalStateException.class, app::start);

        assertEquals(AppState.FAILED, app.state());
        assertEquals(
            List.of("first:start", "second:start", "second:stop", "first:stop"),
            events,
            "The failing hook gets a stop() chance, then started hooks roll back in reverse order");
        // HookLifecycle rethrows Errors as-is; AppRuntimeDefault wraps them in
        // the startup failure, preserving the original Error in the chain.
        assertInstanceOf(AssertionError.class, ex.getCause(),
            "start() failure must preserve the AssertionError, got: " + ex.getCause());
        // close() after a failed start is safe and does not throw again.
        assertDoesNotThrow(app::close);
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void errorDuringHookStartCleansUpThroughBuilder() {
        var events = new CopyOnWriteArrayList<String>();
        var module = new ErrorHookModule(events);

        // Default shutdownHook(true): the registered JVM shutdown hook must be
        // removed and the app closed (container closed) even when start()
        // failed with an Error — previously the Error skipped that cleanup.
        assertThrows(IllegalStateException.class,
            () -> FreewayApp.of(module).start());

        assertEquals(
            List.of("first:start", "second:start", "second:stop", "first:stop"),
            events,
            "rollback must run when a hook fails with an Error");
        assertEquals(1, ErrorHookModule.destroyed,
            "the container must be closed after an Error during startup "
                + "(@PreDestroy proves close() ran)");
    }

    @Test
    void startedEventSubscriberFailureKeepsAppRunning() {
        // AppStartedEvent is a best-effort signal: a throwing subscriber must
        // not abort startup. The EventBus isolates the subscriber exception
        // (logged, counted) and AppRuntimeDefault must not treat it as a
        // startup failure — the app stays RUNNING.
        AppRuntime app = runtime(new ThrowingStartedSubscriberModule());
        app.start();
        assertEquals(AppState.RUNNING, app.state(),
            "a failing AppStartedEvent subscriber must not abort startup");
        app.close();
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void stoppingEventPublishFailureMarksShutdownFailed() {
        // Shutdown must be reliable: a failure while publishing
        // AppStoppingEvent marks shutdown FAILED and close() rethrows.
        // (EventBus isolates subscriber exceptions, so the guarded failure
        // class is a publish-level failure — here a closed bus.)
        AppRuntime app = runtime(new NoopModule());
        app.start();
        assertEquals(AppState.RUNNING, app.state());
        app.get(EventBus.class).close();

        RuntimeException ex = assertThrows(
            RuntimeException.class, app::close);
        assertTrue(ex.getMessage().contains("Failed to publish AppStoppingEvent"),
            "got: " + ex.getMessage());
        assertEquals(AppState.FAILED, app.state());
    }

    /** Subscribes to {@link AppStartedEvent} and throws — startup must not fail. */
    public static final class ThrowingStartedSubscriberModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(AppStartedEvent.class, event -> {
                    throw new IllegalStateException("subscriber boom");
                }));
        }
    }

    /** Minimal module with no lifecycle contribution. */
    public static final class NoopModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
        }
    }

    /** Hook calls {@link AppRuntime#close()} reentrantly from start(). */
    public static final class ReentrantCloseModule implements ModuleEx {
        volatile AppRuntime runtime;
        private final List<Object> events;

        ReentrantCloseModule(List<Object> events) {
            this.events = events;
        }

        @Override
        public void bind(Binder binder) {
            binder.contribute(EventSubscriber.class)
                .add(EventSubscriber.of(AppStartedEvent.class, events::add))
                .add(EventSubscriber.of(AppStoppingEvent.class, events::add));
            binder.contribute(RuntimeHook.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) {
                    runtime.close();
                }
            });
        }
    }

    /** First hook starts cleanly; second hook fails with an {@link AssertionError}. */
    public static final class ErrorHookModule implements ModuleEx {
        static int destroyed;
        private final List<String> events;

        ErrorHookModule(List<String> events) {
            this.events = events;
        }

        @Override
        public void bind(Binder binder) {
            destroyed = 0;
            binder.bind(DestroyMarker.class).to(DestroyMarker.class);
            binder.contribute(RuntimeHook.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) {
                    container.get(DestroyMarker.class); // realize for @PreDestroy
                    events.add("first:start");
                }

                @Override
                public void stop(Container container) {
                    events.add("first:stop");
                }
            });
            binder.contribute(RuntimeHook.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) {
                    events.add("second:start");
                    throw new AssertionError("boom");
                }

                @Override
                public void stop(Container container) {
                    events.add("second:stop");
                }
            });
        }

        public static final class DestroyMarker {
            @PreDestroy
            void destroy() {
                destroyed++;
            }
        }
    }
}
