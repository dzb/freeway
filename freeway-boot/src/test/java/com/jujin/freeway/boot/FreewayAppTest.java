package com.jujin.freeway.boot;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventSubscriber;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Value;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreewayAppTest {
    private static final String APP_NAME_KEY = "app.name";
    private static final String SERVER_PORT_KEY = "server.port";
    private static final String APP_DESCRIPTION_KEY = "app.description";
    private static final String APP_VERSION_KEY = "app.version";
    private static final String SERVER_HOST_KEY = "server.host";

    private String previousAppName;
    private static List<String> hookEvents;

    @BeforeEach
    void capture() {
        previousAppName = System.getProperty(APP_NAME_KEY);
    }

    @AfterEach
    void restore() {
        if (previousAppName == null) {
            System.clearProperty(APP_NAME_KEY);
        } else {
            System.setProperty(APP_NAME_KEY, previousAppName);
        }
    }

    @Test
    void bootsApplicationWithExplicitModules() {
        AppRuntime app = FreewayApp.run(
            new String[]{"--freeway.profile=dev", "--app.name=Overridden"},
            new TestBootApp()
        );
        try {
            assertEquals(AppState.RUNNING, app.state());
            assertTrue(app.isRunning());
            SymbolSource symbolSource = app.get(SymbolSource.class);

            assertEquals("Overridden", symbolSource.resolve(APP_NAME_KEY));
            assertEquals("9191", symbolSource.resolve(SERVER_PORT_KEY));
            assertEquals("Profiled IoC container", symbolSource.resolve(APP_DESCRIPTION_KEY));
            assertEquals("1.0.0", symbolSource.resolve(APP_VERSION_KEY));
            assertEquals("dev.localhost", symbolSource.resolve(SERVER_HOST_KEY));
            assertEquals(List.of("dev"), app.config().profiles());
            assertEquals("dev", app.config().snapshot().get("freeway.profile"));
            assertEquals("Overridden", app.config().snapshot().get(APP_NAME_KEY));
            assertEquals("9191", app.config().snapshot().get(SERVER_PORT_KEY));

            Greeter greeter = app.get(Greeter.class);
            assertEquals("Hello, World!", greeter.greet("World"));
        } finally {
            app.close();
        }
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void autoDiscoveryEnabledIncludesSPI() {
        AppRuntime app = FreewayApp.of()
            .add(new TestBootApp())
            .args("--freeway.profile=dev", "--app.name=Overridden")
            .start();
        try {
            AutoMarker marker = app.get(AutoMarker.class);
            assertEquals("auto", marker.value());
        } finally {
            app.close();
        }
    }

    @Test
    void autoDiscoveryDisabledExcludesSPI() {
        AppRuntime app = FreewayApp.of()
            .add(new TestBootApp())
            .args("--app.name=Default")
            .autoDiscovery(false)
            .start();
        try {
            // AutoModule should NOT be loaded when ServiceLoader is skipped
            assertDoesNotThrow(() -> app.get(Greeter.class));
            assertThrows(Exception.class, () -> app.get(AutoMarker.class));
        } finally {
            app.close();
        }
    }

    @Test
    void primaryModuleMayBePassedAsInstance() {
        AppRuntime app = FreewayApp.run(
            new InstancePrimaryModule()
        );
        try {
            assertEquals(AppState.RUNNING, app.state());
            assertEquals("instance", app.get(PrimaryMarker.class).value());
        } finally {
            app.close();
        }
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void builderWithShutdownHookDisabled() {
        AppRuntime app = FreewayApp.of()
            .add(new InstancePrimaryModule())
            .shutdownHook(false)
            .start();
        try {
            assertEquals(AppState.RUNNING, app.state());
        } finally {
            app.close();
        }
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void builderIsSingleUse() {
        // Regression: a second start() silently registered another shutdown
        // hook and built an independent container — no guard existed.
        AppBuilder builder = FreewayApp.of().shutdownHook(false);
        AppRuntime first = builder.start();
        first.close();
        IllegalStateException ex = assertThrows(
            IllegalStateException.class, builder::start);
        assertTrue(ex.getMessage().contains("single-use"),
            "got: " + ex.getMessage());
    }

    @Test
    void concurrentStartAllowsExactlyOneWinner() throws Exception {
        // Regression: the single-use guard was a check-then-set boolean — two
        // threads calling start() concurrently could both pass it, building
        // two containers and registering two shutdown hooks. The guard must
        // be atomic: exactly one call succeeds, the other throws the same
        // single-use error.
        AppBuilder builder = FreewayApp.of(new TestBootApp()).shutdownHook(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AppRuntime>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(builder::start));
            }
            AppRuntime winner = null;
            int failures = 0;
            for (Future<AppRuntime> future : futures) {
                try {
                    winner = future.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException ex) {
                    failures++;
                    assertInstanceOf(IllegalStateException.class, ex.getCause(),
                        "the loser must fail with the single-use guard error, got: "
                            + ex.getCause());
                }
            }
            assertEquals(1, failures, "exactly one concurrent start() must fail");
            assertNotNull(winner, "exactly one concurrent start() must succeed");
            assertTrue(winner.isRunning());
            // The winning app is fully usable — its container resolves services.
            assertEquals("Hello, World!", winner.get(Greeter.class).greet("World"));
            winner.close();
            assertEquals(AppState.STOPPED, winner.state());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void autoDiscoveryCanStartWithoutExplicitModules() {
        AppRuntime app = FreewayApp.of().start();
        try {
            assertEquals(AppState.RUNNING, app.state());
            AutoMarker marker = app.get(AutoMarker.class);
            assertEquals("auto", marker.value());
        } finally {
            app.close();
        }
        assertEquals(AppState.STOPPED, app.state());
    }

    public static class ValueHolder {
        @Value("${server.port}")
        String port;
    }

    public static class ValueHolderModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(ValueHolder.class).to(ValueHolder.class);
        }
    }

    @Test
    void valueInjectionFollowsDocumentedCascadePriority() {
        // A JVM-level system property must not outrank the documented
        // config cascade (CLI > env > files) for @Value/@Symbol injection.
        System.setProperty(SERVER_PORT_KEY, "8080");
        try {
            AppRuntime app = FreewayApp.run(
                new String[]{"--server.port=7070"},
                new ValueHolderModule()
            );
            try {
                ValueHolder holder = app.get(ValueHolder.class);
                assertEquals("7070", holder.port,
                    "@Value must honor the CLI argument over the JVM system property");
                assertEquals("7070", app.config().snapshot().get(SERVER_PORT_KEY));
            } finally {
                app.close();
            }
        } finally {
            System.clearProperty(SERVER_PORT_KEY);
        }
    }

    @Test
    void runtimeHooksStartAndStopInOrder() {
        hookEvents = new ArrayList<>();

        AppRuntime app = FreewayApp.run(
            new HookOrderedModule()
        );

        assertEquals(List.of("first:start", "second:start"), hookEvents);
        app.close();
        assertEquals(List.of("first:start", "second:start", "second:stop", "first:stop"), hookEvents);
        assertEquals(AppState.STOPPED, app.state());
        hookEvents = null;
    }

    @Test
    void runtimeHookStartFailureRollsBackStartedHooks() {
        hookEvents = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> FreewayApp.run(
            new HookFailureModule()
        ));

        assertEquals(
            List.of("first:start", "second:start", "second:stop", "first:stop"),
            hookEvents,
            "The failing hook must get a stop() chance, then started hooks roll back in reverse order"
        );
        hookEvents = null;
    }

    @Test
    void runtimeHookResolutionFailureFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> FreewayApp.run(
            new HookCycleModule()
        ));

        assertTrue(ex.getMessage().contains("Application startup failed"));
    }

    @Test
    void runtimeHookOrderingReferenceToUnknownIdFailsStartup() {
        // Regression (AGENTS.md): invalid hook configuration must fail
        // startup, not WARN and run hooks in insertion order. A typo like
        // after("freeway.http.serve") must surface the missing id.
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> FreewayApp.run(
            new HookUnknownRefModule()
        ));

        assertTrue(ex.getMessage().contains("Application startup failed"),
            "got: " + ex.getMessage());
        Throwable cause = ex.getCause();
        assertTrue(cause != null && cause.getMessage() != null
                && cause.getMessage().contains("nonexistent.hook"),
            "the failure must name the missing id, got: "
                + (cause == null ? null : cause.getMessage()));
    }

    @Test
    void distinctExplicitInstancesOfSameModuleClassFailFast() {
        // Regression: add(new DbModule("ds1"), new DbModule("ds2")) silently
        // dropped the second instance (and its configuration).
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> FreewayApp.of(new DupModule("a"), new DupModule("b")).start());

        assertTrue(ex.getMessage().contains("added twice"),
            "got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(DupModule.class.getName()),
            "message must name the module class, got: " + ex.getMessage());
    }

    @Test
    void sameExplicitInstanceAddedTwiceIsDeduplicated() {
        // Re-adding the identical instance is a harmless user mistake — keep
        // a single copy instead of failing (mirrors ContainerImpl).
        var module = new DupModule("a");
        AppRuntime app = FreewayApp.of(module, module).start();
        try {
            assertEquals("a", app.get(DupMarker.class).value());
        } finally {
            app.close();
        }
    }

    @Test
    void explicitModuleWinsOverSpiDiscoveredSameClass() {
        // FreewayAppTest$SpiDupModule is registered as an SPI ModuleEx; the
        // explicitly added instance must win over the discovered one.
        AppRuntime app = FreewayApp.of().add(new SpiDupModule("explicit")).start();
        try {
            assertEquals("explicit", app.get(SpiDupMarker.class).value(),
                "the explicit instance must win over the SPI-discovered one");
        } finally {
            app.close();
        }
    }

    @Test
    void builderWithCustomConfig() {
        AppRuntime app = FreewayApp.of()
            .add(new InstancePrimaryModule())
            .config((loader, args) -> new AppConfigDefault(
                Map.of("custom.key", "custom-value"), List.of()))
            .start();
        try {
            assertEquals("custom-value", app.config().snapshot().get("custom.key"));
        } finally {
            app.close();
        }
    }

    @Test
    void closeIsIdempotent() {
        AppRuntime app = FreewayApp.run(new TestBootApp());
        app.close();
        assertDoesNotThrow(app::close);
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void closeAfterFailedShutdownIsNoop() {
        AppRuntime app = FreewayApp.run(new HookStopFailureModule());
        assertThrows(RuntimeException.class, app::close);
        assertEquals(AppState.FAILED, app.state());
        // A second close must not re-run shutdown and double-close the container.
        assertDoesNotThrow(app::close);
    }

    public record PrimaryMarker(String value) {}
    public record AutoMarker(String value) {}

    interface Greeter {
        String greet(String name);
    }

    public static final class GreeterImpl implements Greeter {
        @Override
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    public static final class Store {
        private final Greeter greeter;

        public Store(Greeter greeter) {
            this.greeter = greeter;
        }

        String greet(String name) {
            return greeter.greet(name);
        }
    }

    public static final class TestBootApp implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Greeter.class).to(GreeterImpl.class);
            binder.bind(Store.class).to(Store.class);
        }
    }

    public static final class InstancePrimaryModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(PrimaryMarker.class).to(new PrimaryMarker("instance"));
        }
    }

    public static final class HookOrderedModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(RuntimeHook.class).add("second", new RuntimeHook() {
                @Override
                public void start(Container container) { hookEvents.add("second:start"); }
                @Override
                public void stop(Container container) { hookEvents.add("second:stop"); }
            }).after("first");
            binder.contribute(RuntimeHook.class).add("first", new RuntimeHook() {
                @Override
                public void start(Container container) { hookEvents.add("first:start"); }
                @Override
                public void stop(Container container) { hookEvents.add("first:stop"); }
            });
        }
    }

    public static final class HookFailureModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(RuntimeHook.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) { hookEvents.add("first:start"); }
                @Override
                public void stop(Container container) { hookEvents.add("first:stop"); }
            });
            binder.contribute(RuntimeHook.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) {
                    hookEvents.add("second:start");
                    throw new IllegalStateException("boom");
                }
                @Override
                public void stop(Container container) {
                    hookEvents.add("second:stop");
                }
            });
        }
    }

    public static final class HookStopFailureModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(RuntimeHook.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) {}
                @Override
                public void stop(Container container) {
                    throw new IllegalStateException("stop boom");
                }
            });
        }
    }

    public static final class HookCycleModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(RuntimeHook.class).add("first", new RuntimeHook() {
                @Override
                public void start(Container container) {}
            }).after("second");
            binder.contribute(RuntimeHook.class).add("second", new RuntimeHook() {
                @Override
                public void start(Container container) {}
            }).after("first");
        }
    }

    public static final class HookUnknownRefModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(RuntimeHook.class).add("myhook", new RuntimeHook() {
                @Override
                public void start(Container container) {}
            }).after("nonexistent.hook");
        }
    }

    public record DupMarker(String value) {}

    public static final class DupModule implements ModuleEx {
        private final String value;

        DupModule(String value) {
            this.value = value;
        }

        @Override
        public void bind(Binder binder) {
            binder.bind(DupMarker.class).to(new DupMarker(value));
        }
    }

    public record SpiDupMarker(String value) {}

    /** Registered as an SPI ModuleEx provider (see META-INF/services). */
    public static final class SpiDupModule implements ModuleEx {
        private final String value;

        public SpiDupModule() {
            this("spi");
        }

        public SpiDupModule(String value) {
            this.value = value;
        }

        @Override
        public void bind(Binder binder) {
            binder.bind(SpiDupMarker.class).to(new SpiDupMarker(value));
        }
    }

    @Test
    void startAfterStopIsRejected() {
        AppRuntime app = FreewayApp.of(new TestBootApp()).start();
        app.close();
        assertEquals(AppState.STOPPED, app.state());
        assertThrows(IllegalStateException.class, app::start,
            "a stopped application must not restart");
    }

    @Test
    void lifecycleEventsArePublished() {
        var events = new CopyOnWriteArrayList<Object>();
        AppRuntime app = FreewayApp.of(new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                binder.contribute(EventSubscriber.class)
                    .add(EventSubscriber.of(AppStartedEvent.class, events::add))
                    .add(EventSubscriber.of(AppStoppingEvent.class, events::add));
            }
        }).start();
        app.close();

        assertTrue(events.stream().anyMatch(e -> e instanceof AppStartedEvent),
            "AppStartedEvent must be published on start");
        assertTrue(events.stream().anyMatch(e -> e instanceof AppStoppingEvent),
            "AppStoppingEvent must be published before shutdown");
    }
}
