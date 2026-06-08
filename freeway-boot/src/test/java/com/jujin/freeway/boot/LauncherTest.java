package com.jujin.freeway.boot;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Module;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.RuntimeHooks;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherTest {
    private static final String APP_NAME_KEY = "app.name";
    private static final String SERVER_PORT_KEY = "server.port";
    private static final String APP_DESCRIPTION_KEY = "app.description";
    private static final String APP_VERSION_KEY = "app.version";
    private static final String SERVER_HOST_KEY = "server.host";

    private String previousAppName;

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
    void bootsApplicationWithPrimaryAndDiscoveredModules() {
        AppRuntime app = Launcher.run(TestBootApp.class, "--freeway.profile=dev", "--app.name=Overridden");
        try {
            assertEquals(AppState.RUNNING, app.state());
            assertTrue(app.running());
            Container container = app.container();
            SymbolSource symbolSource = container.get(SymbolSource.class);

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

            Greeter greeter = container.get(Greeter.class);
            assertEquals("Hello, World!", greeter.greet("World"));

            AutoMarker marker = container.get(AutoMarker.class);
            assertEquals("auto", marker.value());
        } finally {
            app.close();
        }
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void primaryModuleMayBePassedAsInstance() {
        AppRuntime app = Launcher.run(new Module() {
            @Override
            public void bind(Binder binder) {
                binder.bind(PrimaryMarker.class).to(new PrimaryMarker("instance"));
            }
        });
        try {
            assertEquals(AppState.RUNNING, app.state());
            assertEquals("instance", app.container().get(PrimaryMarker.class).value());
        } finally {
            app.close();
        }
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void runtimeHooksStartAndStopInOrder() {
        List<String> events = new ArrayList<>();

        AppRuntime app = Launcher.run(binder -> {
            binder.contribute(RuntimeHooks.class).add("second", new RuntimeHook() {
                @Override
                public void start(Container container) {
                    events.add("second:start");
                }

                @Override
                public void stop(Container container) {
                    events.add("second:stop");
                }
            }).after("first");
            binder.contribute(RuntimeHooks.class).add("first", new RuntimeHook() {
                @Override
                public void start(Container container) {
                    events.add("first:start");
                }

                @Override
                public void stop(Container container) {
                    events.add("first:stop");
                }
            });
        });

        assertEquals(List.of("first:start", "second:start"), events);
        app.close();
        assertEquals(List.of("first:start", "second:start", "second:stop", "first:stop"), events);
        assertEquals(AppState.STOPPED, app.state());
    }

    @Test
    void runtimeHookStartFailureRollsBackStartedHooks() {
        List<String> events = new ArrayList<>();

        assertThrows(IllegalStateException.class, () -> Launcher.run(binder -> {
            binder.contribute(RuntimeHooks.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) {
                    events.add("first:start");
                }

                @Override
                public void stop(Container container) {
                    events.add("first:stop");
                }
            });
            binder.contribute(RuntimeHooks.class).add(new RuntimeHook() {
                @Override
                public void start(Container container) {
                    events.add("second:start");
                    throw new IllegalStateException("boom");
                }
            });
        }));

        assertEquals(List.of("first:start", "second:start", "first:stop"), events);
    }

    public record PrimaryMarker(String value) {
    }

    public record AutoMarker(String value) {
    }

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

    public static final class TestBootApp implements Module {
        @Override
        public void bind(Binder binder) {
            binder.bind(Greeter.class).to(GreeterImpl.class);
            binder.bind(Store.class).to(Store.class);
        }
    }
}
