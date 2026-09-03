package com.jujin.freeway.cloud.context;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.CloudModule;
import com.jujin.freeway.cloud.discovery.Endpoint;
import com.jujin.freeway.cloud.discovery.ServiceInstance;
import com.jujin.freeway.cloud.discovery.ServiceRegistry;
import com.jujin.freeway.cloud.internal.BaggagePropagator;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void wireCodecRoundTripsValuesThatWouldCorruptTheHeader() {
        // Separator characters, whitespace, non-ASCII and the escape char
        // itself must travel as data, not corrupt the wire structure.
        BaggagePropagator propagator = new BaggagePropagator();
        Baggage baggage = Baggage.of(Map.of(
            "tenant", "acme,corp",
            "k=v", "tricky",
            "note", "hello world",
            "unicode", "café\u2603",
            "percent", "50%"));
        InvocationContext ctx = InvocationContext.of(null, null, baggage);

        Map<String, String> headers = new HashMap<>();
        propagator.inject(ctx, headers);

        Baggage restored = propagator.extract(headers).baggage();
        assertEquals(baggage.values(), restored.values(),
            "the wire codec must round-trip arbitrary application baggage");
    }

    @Test
    void malformedEscapeDegradesToRawText() {
        // A foreign peer's malformed escape must not change the failure type
        // the caller sees — extraction degrades to the raw text.
        BaggagePropagator propagator = new BaggagePropagator();
        Baggage restored = propagator.extract(Map.of("baggage", "k=%zz,ok=fine")).baggage();
        assertEquals("%zz", restored.get("k"));
        assertEquals("fine", restored.get("ok"));
    }

    @Test
    void propagationStopsAtTheEntryLimit() {
        // Regression: no entry/length caps meant every hop multiplied header
        // size and memory unboundedly (W3C recommends implementation limits).
        // Injection drops entries beyond the cap; extraction truncates too.
        BaggagePropagator propagator = new BaggagePropagator();
        Map<String, String> oversized = new HashMap<>();
        for (int i = 0; i < BaggagePropagator.MAX_ENTRIES + 10; i++) {
            oversized.put("k" + i, "v" + i);
        }
        InvocationContext ctx = InvocationContext.of(null, null, Baggage.of(oversized));

        Map<String, String> headers = new HashMap<>();
        propagator.inject(ctx, headers);
        String header = headers.get(BaggagePropagator.HEADER_BAGGAGE);
        assertNotNull(header);
        assertTrue(header.split(",").length <= BaggagePropagator.MAX_ENTRIES,
            "injected baggage never exceeds the entry cap");

        Baggage restored = propagator.extract(headers).baggage();
        assertTrue(restored.values().size() <= BaggagePropagator.MAX_ENTRIES,
            "extracted baggage never exceeds the entry cap");
    }

    @Test
    void propagationStopsAtTheLengthLimit() {
        BaggagePropagator propagator = new BaggagePropagator();
        Map<String, String> huge = new HashMap<>();
        huge.put("big", "x".repeat(BaggagePropagator.MAX_ENCODED_LENGTH));
        InvocationContext ctx = InvocationContext.of(null, null, Baggage.of(huge));

        Map<String, String> headers = new HashMap<>();
        propagator.inject(ctx, headers);
        String header = headers.get(BaggagePropagator.HEADER_BAGGAGE);
        assertNull(header,
            "a single entry past the length cap must not be injected at all");
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
