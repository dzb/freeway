package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.CallBus;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the remote-CallBus bridge: a real server container
 * registers CallBus handlers and exports them via {@link RpcEndpoint}; the
 * test drives {@link RemoteCaller} against it over HTTP (design doc §8-C).
 */
class RemoteCallerTest {

    record Greeting(String name) {}

    static class BusinessFailure extends RuntimeException {
        BusinessFailure(String m) { super(m); }
    }

    /** Exposes the server's CallBus mapping through RpcEndpoint. */
    static class RpcExportModule implements ModuleEx {
        private final CallBus binderBindTimeBus = new CallBus(new CallBusContainerShim());

        /** Minimal view serving exactly what the CallBus constructor needs. */
        static final class CallBusContainerShim implements Container {
            private final com.jujin.freeway.commons.metrics.Metrics metrics
                = new com.jujin.freeway.commons.metrics.NoopMetrics();
            @SuppressWarnings("unchecked")
            public <T> T get(Class<T> type) {
                if (type == com.jujin.freeway.commons.metrics.Metrics.class) return (T) metrics;
                throw new UnsupportedOperationException(String.valueOf(type));
            }
            public <T> T get(Class<T> type, String id) { return get(type); }
            @SafeVarargs public final <T> T get(Class<T> type, Class<? extends java.lang.annotation.Annotation>... markers) { return get(type); }
            @Override public <T> boolean isActiveBinding(
                Class<T> type,
                Class<? extends java.lang.annotation.Annotation>... markers) {
                throw new UnsupportedOperationException();
            }
            public <T> com.jujin.freeway.ioc.extension.Extension<T> extension(Class<T> entryType) { throw new UnsupportedOperationException(); }
            public <T> T create(Class<T> type) { throw new UnsupportedOperationException(); }
            public void close() {}
        }

        @Override
        public void bind(Binder binder) {
            // Handlers register NOW (bind time) on the module-owned bus —
            // the same instance RpcEndpoint serves. No lazy-service trap: a
            // fresh bus per module instance is created before bind() runs.
            binderBindTimeBus.register("user", new Handlers());
            binderBindTimeBus.register("order", new OrderHandlers());
            // Two exports in one process: each mapping owns its route, so the
            // second must not collide with the first.
            binder.contribute(com.jujin.freeway.http.route.Route.class)
                .add("rpc-user", RpcEndpoint.of(
                    "user",
                    binderBindTimeBus,
                    new JsonCodecDefault()));
            binder.contribute(com.jujin.freeway.http.route.Route.class)
                .add("rpc-order", RpcEndpoint.of(
                    "order",
                    binderBindTimeBus,
                    new JsonCodecDefault()));
        }
    }

    /** Server-side handlers — a plain object, no interface required.
     *  Must be public: CallBus reflective dispatch honors module access rules
     *  even when the registered instance comes through a lambda/hook. */
    public static class Handlers {
        public Greeting greet(String name) { return new Greeting("hi " + name); }
        public int add(int a, int b) { return a + b; }
        public void fire(String label) { }
        public String boom() { throw new BusinessFailure("overdrawn"); }
    }

    /** Second exported mapping — same bus, different prefix. */
    public static class OrderHandlers {
        public String charge(String id) { return "charged:" + id; }
    }

    private AppRuntime server;
    private RemoteCaller caller;

    @BeforeEach
    void startApps() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
        System.setProperty(CloudConfigKeys.RPC_REQUEST_TIMEOUT, "2000");
        server = FreewayApp.run(new HttpModule(), new CloudModule(), new RpcExportModule());
        var webServer = server.get(com.jujin.freeway.http.WebServer.class);
        caller = new RemoteCaller(server.get(CloudHttpClient.class), new JsonCodecDefault());

        ServiceRegistry registry = server.get(ServiceRegistry.class);
        // The bridge itself is served by the same app here; in production the
        // consumer discovers the *provider's* instances. Same mechanics either way.
        registry.register(ServiceInstance.of(
            "target", "i1",
            Endpoint.of("http", webServer.host(), webServer.port()), java.util.Map.of()));
    }

    @AfterEach
    void stopApps() {
        if (server != null) server.close();
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        System.clearProperty(CloudConfigKeys.RPC_REQUEST_TIMEOUT);
    }

    /** Resolves the test app's own HTTP server as the RPC transport target. */

    @Test
    void roundTripReturnsDeserializedValue() {
        Greeting reply = caller.invoke("target", "user", "greet", List.of("bob"), Greeting.class);
        assertEquals(new Greeting("hi bob"), reply);
    }

    @Test
    void primitiveArgsAndReturnSurviveTheWire() {
        Integer sum = caller.invoke("target", "user", "add", List.of(2, 40), Integer.class);
        assertEquals(42, sum);
    }

    @Test
    void voidHandlerYieldsNull() {
        Object reply = caller.invoke("target", "user", "fire", List.of("x"), Void.class);
        assertNull(reply);
    }

    @Test
    void businessExceptionIsNeverRetryableAndCarriesRemoteClass() {
        CloudException ex = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "boom", List.of(), String.class));
        assertFalse(ex.retryable(), "business failure is deterministic");
        assertEquals(400, ex.status());
        Object cause = ex.getCause();
        assertTrue(cause instanceof RemoteInvocationException,
            "cause must be the rebuilt remote exception, got: " + cause);
        assertEquals(BusinessFailure.class.getName(),
            ((RemoteInvocationException) cause).remoteClass());
    }

    @Test
    void unknownTopicSurfacesAsNotFound() {
        CloudException ex = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "missing", List.of(), String.class));
        assertFalse(ex.retryable(), "no handler is configuration, not transient");
        assertEquals(404, ex.status());
    }

    @Test
    void perCallTimeoutNarrowsTheWait() {
        // Server handler sleeps? Use the slow endpoint via a dedicated service:
        // reuse boom-free greet but with an absurdly short per-call deadline.
        CloudException ex = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "greet", List.of("bob"), Greeting.class,
                java.time.Duration.ofNanos(1)));
        assertTrue(ex.retryable(), "per-call deadline expiry maps to retryable timeout");
    }

    @Test
    void zeroTimeoutFallsBackToTransportDefault() {
        Greeting reply = caller.invoke("target", "user", "greet", List.of("carol"),
            Greeting.class, java.time.Duration.ZERO);
        assertEquals(new Greeting("hi carol"), reply);
    }

    @Test
    void malformedSegmentFailsFastClientSide() {
        assertThrows(IllegalArgumentException.class, () ->
            caller.invoke("target", "us er", "greet", List.of(), String.class));
        assertThrows(IllegalArgumentException.class, () ->
            caller.invoke("target", "user", "gree t", List.of(), String.class));
    }

    @Test
    void secondExportedMappingIsReachable() {
        String reply = caller.invoke("target", "order", "charge", List.of("9"), String.class);
        assertEquals("charged:9", reply);
    }

    @Test
    void exportGateStaysPerMappingPrefix() {
        // order.charge exists, user.charge does not: a sibling's topic must not
        // become reachable just because it lives on the same bus.
        CloudException ex = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "charge", List.of("9"), String.class));
        assertEquals(404, ex.status());
    }

    @Test
    void illegalMappingNameFailsAtExportTime() {
        CallBus bus = new CallBus(new RpcExportModule.CallBusContainerShim());
        assertThrows(IllegalArgumentException.class,
            () -> RpcEndpoint.of("us er", bus, new JsonCodecDefault()));
    }

    @Test
    void encodedControlCharactersInPathStillYieldNotFound() throws Exception {
        // Path segments are URL-decoded before dispatch, so the reject reason
        // arrives here containing CR/LF. Form-encoding the header is what keeps
        // this a 404 instead of the HTTP layer refusing the value as a 500.
        String raw = postRaw("/rpc/user/greet%0d%0aX-Injected%3a%20pwned", "[]");
        assertTrue(raw.startsWith("HTTP/1.1 404"), "status must survive: " + firstLine(raw));
        assertFalse(raw.contains("\r\nX-Injected: pwned\r\n"), "no response header injection");
        assertTrue(raw.contains("X-RPC-Reject-Reason: no+handler+for+topic"),
            "reason must still reach the caller: " + firstLine(raw));
        assertTrue(raw.contains("\"error\""), "body stays JSON: " + firstLine(raw));
    }

    /** Drives the endpoint directly — the consumer validates segments and
     *  cannot express a malformed path. */
    private String postRaw(String path, String body) throws Exception {
        int port = server.get(com.jujin.freeway.http.WebServer.class).port();
        String request = "POST " + path + " HTTP/1.1\r\n"
            + "Host: t\r\n"
            + "X-RPC-Version: 1\r\n"
            + "Content-Type: application/json\r\n"
            + "Content-Length: " + body.length() + "\r\n"
            + "Connection: close\r\n\r\n" + body;
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(
                request.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            socket.shutdownOutput();
            return new String(socket.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    private static String firstLine(String response) {
        return response.substring(0, Math.min(response.indexOf('\r'), response.length()));
    }
}
