package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.*;
import com.jujin.freeway.ioc.annotation.Value;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
            assertEquals("dev", app.config().get("freeway.profile"));
            assertEquals("Overridden", app.config().get(APP_NAME_KEY));
            assertEquals("9191", app.config().get(SERVER_PORT_KEY));
            assertEquals("Overridden", app.config().asMap().get(APP_NAME_KEY));

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
                assertEquals("7070", app.config().get(SERVER_PORT_KEY));
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

        assertEquals(List.of("first:start", "second:start", "first:stop"), hookEvents);
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
    void builderWithCustomConfig() {
        AppRuntime app = FreewayApp.of()
            .add(new InstancePrimaryModule())
            .config((loader, args) -> new AppConfigDefault(
                Map.of("custom.key", "custom-value"), List.of()))
            .start();
        try {
            assertEquals("custom-value", app.config().get("custom.key"));
        } finally {
            app.close();
        }
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
}
