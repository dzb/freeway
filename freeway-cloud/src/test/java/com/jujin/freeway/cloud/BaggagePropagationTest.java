package com.jujin.freeway.cloud;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.context.Baggage;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.rpc.CloudHttpClient;
import com.jujin.freeway.cloud.rpc.CloudRequest;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * W3C {@code baggage} end-to-end: inbound extraction restores the baggage
 * into the request scope; the CloudHttpClient outbound call re-injects it, so
 * application-owned KV crosses the service boundary in both directions.
 */
class BaggagePropagationTest {

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @BeforeEach
    void resetSeen() {
        // Static capture slots are shared across tests — a previous test's
        // observed baggage would otherwise leak into the no-header assertion.
        BaggageModule.ENTRY_SEEN.set(null);
        BaggageModule.CALLEE_SEEN.set(null);
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    @Test
    void inboundExtractionAndOutboundInjectionCarryTheSameBaggage() throws Exception {
        try (AppRuntime app = FreewayApp.run(
            new BaggageModule(), new HttpModule(), new CloudModule())) {
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("svc", "i1",
                    Endpoint.of("http", "127.0.0.1", app.get(com.jujin.freeway.http.WebServer.class).port()),
                    Map.of()));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.get(com.jujin.freeway.http.WebServer.class).port()
                        + "/api/call"))
                .header("baggage", "tenant=acme,region=cn-north")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            // Inbound: the entry handler saw the extracted baggage.
            Baggage entry = BaggageModule.ENTRY_SEEN.get();
            assertEquals("acme", entry.get("tenant"));
            assertEquals("cn-north", entry.get("region"));

            // Outbound: the callee handler saw the same baggage (re-injected).
            Baggage callee = BaggageModule.CALLEE_SEEN.get();
            assertEquals("acme", callee.get("tenant"));
            assertEquals("cn-north", callee.get("region"));
        }
    }

    @Test
    void noBaggageHeaderLeavesTheContextAbsent() throws Exception {
        try (AppRuntime app = FreewayApp.run(
            new BaggageModule(), new HttpModule(), new CloudModule())) {
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("svc", "i1",
                    Endpoint.of("http", "127.0.0.1", app.get(com.jujin.freeway.http.WebServer.class).port()),
                    Map.of()));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.get(com.jujin.freeway.http.WebServer.class).port()
                        + "/api/call"))
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            assertNull(BaggageModule.ENTRY_SEEN.get(),
                "no baggage header must not fabricate baggage");
        }
    }

    static class BaggageModule implements ModuleEx {
        static final AtomicReference<Baggage> ENTRY_SEEN = new AtomicReference<>();
        static final AtomicReference<Baggage> CALLEE_SEEN = new AtomicReference<>();

        @Override
        public void bind(Binder b) {
            b.contribute(Route.class).add(Route.get("/api/call", CallHandler.class));
            b.contribute(Route.class).add(Route.get("/api/echo", ctx -> {
                CALLEE_SEEN.set(InvocationContext.current().map(InvocationContext::baggage).orElse(null));
                ctx.send(200, "ok");
            }));
        }

        static class CallHandler implements RouteHandler {
            private final CloudHttpClient client;

            CallHandler(CloudHttpClient client) {
                this.client = client;
            }

            @Override
            public void handle(HttpContext ctx) throws Exception {
                ENTRY_SEEN.set(InvocationContext.current().map(InvocationContext::baggage).orElse(null));
                client.call("svc", CloudRequest.get("/api/echo"));
                ctx.send(200, "ok");
            }
        }
    }
}
