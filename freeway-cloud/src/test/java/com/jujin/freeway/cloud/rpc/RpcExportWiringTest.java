package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.CallBus;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wiring contracts of the export declaration: what the framework guarantees
 * once an application declares {@link RpcExport} — and what it refuses to
 * start with.
 *
 * <p>These are the properties that replaced a hand-wired bus: registration
 * happens on the container's bus, a wiring mistake fails startup with an
 * actionable message, and nothing depends on runtime-hook ordering.
 */
class RpcExportWiringTest {

    public static class UserHandlers {
        public String greet(String name) { return "hi " + name; }
    }

    public static class OtherHandlers {
        public String charge(String id) { return "charged:" + id; }
    }

    private AppRuntime app;

    @AfterEach
    void stop() {
        if (app != null) {
            app.close();
            app = null;
        }
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.RPC_REQUEST_TIMEOUT);
    }

    private AppRuntime run(ModuleEx... modules) {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
        ModuleEx[] all = new ModuleEx[modules.length + 2];
        all[0] = new HttpModule();
        all[1] = new CloudModule();
        System.arraycopy(modules, 0, all, 2, modules.length);
        return FreewayApp.run(all);
    }

    private static void pointDiscoveryAt(AppRuntime app, String serviceId) {
        WebServer web = app.get(WebServer.class);
        app.get(ServiceRegistry.class).register(ServiceInstance.of(
            serviceId, "i1", Endpoint.of("http", web.host(), web.port()), Map.of()));
    }

    /** The module an application writes: bind the handler, declare the export. */
    static class UserExports implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(UserHandlers.class);
            binder.contribute(RpcExport.class).add(RpcExport.of("user", UserHandlers.class));
        }
    }

    @Test
    void handlersRegisterOnTheContainerBus() {
        app = run(new UserExports());

        // The invariant the hand-wired bus could not guarantee: the route
        // dispatches on the very bus the container hands out, so a local-first
        // client and the endpoint can never look at different buses.
        assertTrue(app.get(CallBus.class).handles("user.greet"),
            "the export must register its handler on the container's CallBus");
    }

    @Test
    void unexportedMappingAnswersNotFound() throws Exception {
        app = run(new UserExports());
        pointDiscoveryAt(app, "target");

        // "order" was never declared, even though a handler type for it exists
        // in this test — export is explicit, so it stays unreachable.
        var caller = app.get(RemoteCaller.class);
        var ex = assertThrows(CloudException.class, () ->
            caller.invoke("target", "order", "charge", List.of("9"), String.class));
        assertEquals(404, ex.status(),
            "an undeclared mapping must not become reachable by accident");
    }

    @Test
    void declaredExportIsReachableOverTheFrameworkRoute() throws Exception {
        app = run(new UserExports());
        pointDiscoveryAt(app, "target");

        String reply = app.get(RemoteCaller.class)
            .invoke("target", "user", "greet", List.of("bob"), String.class);
        assertEquals("hi bob", reply);
    }

    @Test
    void duplicateMappingFailsStartup() {
        // Two declarations of one mapping would silently hot-swap handlers on
        // the bus (register replaces per method), so it stops startup instead.
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            run(new UserExports(), new ModuleEx() {
                @Override
                public void bind(Binder binder) {
                    binder.bind(OtherHandlers.class);
                    binder.contribute(RpcExport.class)
                        .add(RpcExport.of("user", OtherHandlers.class));
                }
            }));

        assertTrue(chainMessages(failure).contains("exported twice"),
            "the failure must name the collision, got: " + chainMessages(failure));
    }

    @Test
    void unboundExportTypeFailsStartupWithTheFix() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            run(new ModuleEx() {
                @Override
                public void bind(Binder binder) {
                    // Declared but never bound: the handler cannot be resolved.
                    binder.contribute(RpcExport.class)
                        .add(RpcExport.of("ghost", UserHandlers.class));
                }
            }));

        String message = chainMessages(failure);
        assertTrue(message.contains("not bound") && message.contains("binder.bind(UserHandlers.class)"),
            "the failure must say what to add, got: " + message);
    }

    @Test
    void routeIsImmuneToHookOrdering() throws Exception {
        // A hook that resolves WebServer before the export hook freezes the
        // route index early. The export route is contributed at bind time, so
        // it is already in that index — this is the property that replaced the
        // "add a route from a hook" design, which would have lost the route
        // silently here.
        app = run(new UserExports(), new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                binder.contribute(RuntimeHook.class)
                    .add("test.freeze-routes-early", new RuntimeHook() {
                        @Override
                        public void start(Container container) {
                            container.get(WebServer.class);
                        }

                        @Override
                        public void stop(Container container) {
                        }
                    })
                    .before(CloudHooks.RPC);
            }
        });
        pointDiscoveryAt(app, "target");

        String reply = app.get(RemoteCaller.class)
            .invoke("target", "user", "greet", List.of("ada"), String.class);
        assertEquals("hi ada", reply,
            "the export route must survive an early route-index freeze");
    }

    /**
     * Every message in a startup failure's cause chain, joined — the runtime
     * wraps hook failures, and the actionable text sits on the framework's own
     * exception, not necessarily on the deepest cause.
     */
    private static String chainMessages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            all.append(current.getMessage()).append(" | ");
        }
        return all.toString();
    }
}
