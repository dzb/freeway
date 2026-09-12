package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.CloudModules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudConfigKeys;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wire contract tests for remote invocation: a real server container declares
 * {@link RpcExport RPC exports} (the framework resolves the handlers and serves
 * them through {@link RpcEndpoint}); the test drives {@link RemoteCaller}
 * against it over HTTP (design doc §8-C).
 */
class RemoteCallerTest {

    record Greeting(String name) {}

    static class BusinessFailure extends RuntimeException {
        BusinessFailure(String m) { super(m); }
    }

    /**
     * Exposes the server's mappings the application way: bind the handlers and
     * declare the exports; the framework resolves them from the container and
     * serves them. Nothing here holds a handler, a codec or a route — that is
     * the point of the export declaration.
     */
    static class RpcExportModule implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.bind(Handlers.class);
            binder.bind(OrderHandlers.class);
            // Two exports in one process: the declarations do not collide, and
            // each keeps its own mapping.
            binder.contribute(RpcExport.class).add(RpcExport.of("user", Handlers.class));
            binder.contribute(RpcExport.class).add(RpcExport.of("order", OrderHandlers.class));
        }
    }

    /** Server-side handlers — a plain object, no interface required.
     *  Must be public: reflective dispatch through method handles honors
     *  module access rules even when the instance comes from the container. */
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
        server = FreewayApp.of(new HttpModule()).add(CloudModules.standard()).add(new RpcExportModule()).start();
        var webServer = server.get(com.jujin.freeway.http.WebServer.class);
        caller = server.get(RemoteCaller.class);   // framework-bound, not hand-wired

        ServiceRegistry registry = server.get(ServiceRegistry.class);
        // The provider is served by the same app here; in production the
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
        // order.charge exists, user.charge does not: a sibling mapping's method
        // must not become reachable under another mapping's name.
        CloudException ex = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "charge", List.of("9"), String.class));
        assertEquals(404, ex.status());
    }

    @Test
    void failureKindsSeparateWhatTheSharedFlagsCannot() {
        // No instance / circuit open / rate limited share retryable=false,
        // outcomeUnknown=false, status=-1, cause=null — only kind() tells them
        // apart, which is what an operator's next action depends on.
        assertEquals(CloudException.Kind.NO_INSTANCE,
            CloudException.noInstance("svc").kind());
        assertEquals(CloudException.Kind.CIRCUIT_OPEN,
            CloudException.circuitOpen("svc").kind());
        assertEquals(CloudException.Kind.RATE_LIMITED,
            CloudException.rateLimited("svc").kind());
        assertEquals(CloudException.Kind.CONNECT,
            CloudException.connect("svc", new java.io.IOException("refused")).kind());
        assertEquals(CloudException.Kind.TIMEOUT, CloudException.timeout("svc").kind());
        assertEquals(CloudException.Kind.TRANSPORT,
            CloudException.transport("svc", new java.io.IOException("reset")).kind());
    }

    @Test
    void eachWireFailureCarriesItsOwnKind() {
        // Business failure: the handler threw (class crosses, wrapped as cause).
        CloudException business = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "boom", List.of(), String.class));
        assertEquals(CloudException.Kind.BUSINESS, business.kind());
        assertTrue(business.getCause() instanceof RemoteInvocationException);

        // Rejected shape: the peer has no such method.
        CloudException missing = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "missing", List.of(), String.class));
        assertEquals(CloudException.Kind.REJECTED, missing.kind());

        // Unreadable reply: the declared type does not match the answer.
        CloudException unreadable = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "greet", List.of("bob"), Integer.class));
        assertEquals(CloudException.Kind.REPLY_UNREADABLE, unreadable.kind());
    }

    @Test
    void wrongArgumentCountIsARejectedCallNotAServerError() {
        // The wire carries a positional array, so arity is part of the contract:
        // a mismatch is a bad call (400, deterministic, never replayed), not a
        // handler failure to be retried or a 500 to be investigated as an outage.
        CloudException ex = assertThrows(CloudException.class, () ->
            caller.invoke("target", "user", "add", List.of(1), Integer.class));
        assertFalse(ex.retryable(), "a malformed call is deterministic");
        assertEquals(400, ex.status());
        assertTrue(ex.getCause() instanceof RemoteInvocationException,
            "the failing class still crosses, so the caller can see what was wrong");
    }

    @Test
    void illegalMappingNameFailsAtDeclarationTime() {
        // The declaration is where a bad mapping belongs: it names a path
        // segment, so it must fail when the module binds, not per request.
        assertThrows(IllegalArgumentException.class,
            () -> RpcExport.of("us er", Handlers.class));
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
