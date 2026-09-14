package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.CloudModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudHooks;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.WebServer;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.ArrayList;
import com.jujin.freeway.ioc.ModuleNode;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Wiring contracts of the export declaration: what the framework guarantees
 * once an application declares {@link RpcExport} — and what it refuses to
 * start with.
 *
 * <p>The endpoint serves the handlers the container resolved (injected, one
 * instance, container-owned lifecycle) and nothing else: only declared
 * mappings exist, and every wiring mistake that can be seen at startup stops
 * the boot with an actionable message.
 */
class RpcExportWiringTest {

    public static class UserHandlers {
        public String greet(String name) { return "hi " + name; }
    }

    public static class OtherHandlers {
        public String charge(String id) { return "charged:" + id; }
    }

    /** Counts its own calls, so the test can see handler identity across calls. */
    public static class Counter {
        private int hits;

        public synchronized String next() { return "hit-" + (++hits); }
    }

    /** Handler with a dependency: only container resolution can build it. */
    public static class InjectedHandlers {
        private final Counter counter;

        @Inject
        public InjectedHandlers(Counter counter) { this.counter = counter; }

        public String hit() { return counter.next(); }
    }

    /** Two public methods with one name: the positional wire cannot split them. */
    public static class OverloadedHandlers {
        public String pick(String a) { return a; }
        public String pick(String a, String b) { return a + b; }
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
        List<ModuleNode> children = new ArrayList<>();
        children.add(ModuleNode.of(new HttpModule()));
        children.add(ModuleNode.of(CloudModule.class));
        for (ModuleEx module : modules) {
            children.add(ModuleNode.of(module));
        }
        return FreewayApp.run(ModuleNode.app("test", children.toArray(ModuleNode[]::new)));
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
    void endpointServesTheContainerResolvedHandler() throws Exception {
        app = run(new ModuleEx() {
            @Override
            public void bind(Binder binder) {
                binder.bind(Counter.class);
                binder.bind(InjectedHandlers.class);
                binder.contribute(RpcExport.class)
                    .add(RpcExport.of("count", InjectedHandlers.class));
            }
        });
        pointDiscoveryAt(app, "target");

        var caller = app.get(RemoteCaller.class);
        // Constructor injection happened (the handler has no default constructor)
        // and both calls reached the same container-managed instance: the
        // endpoint serves the service, not a per-request copy.
        assertEquals("hit-1", caller.invoke("target", "count", "hit", List.of(), String.class));
        assertEquals("hit-2", caller.invoke("target", "count", "hit", List.of(), String.class));
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
        // Two declarations of one mapping would leave the second handler
        // unreachable (the first one owns the name), so it stops startup
        // instead of silently serving one of them.
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
    void overloadedHandlerMethodFailsStartup() {
        // Positional arguments cannot disambiguate overloads: serving one of
        // them silently would be a coin flip, so the boot stops instead.
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
            run(new ModuleEx() {
                @Override
                public void bind(Binder binder) {
                    binder.bind(OverloadedHandlers.class);
                    binder.contribute(RpcExport.class)
                        .add(RpcExport.of("picky", OverloadedHandlers.class));
                }
            }));

        assertTrue(chainMessages(failure).contains("overloads"),
            "the failure must explain the positional contract, got: " + chainMessages(failure));
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
