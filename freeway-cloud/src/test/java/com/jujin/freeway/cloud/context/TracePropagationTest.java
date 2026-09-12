package com.jujin.freeway.cloud.context;

import com.jujin.freeway.cloud.CloudModules;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * W3C traceparent end-to-end: inbound extraction binds the context for the
 * request scope; the CloudHttpClient outbound call re-injects it, so the
 * callee sees the same trace.
 */
class TracePropagationTest {

    private static final String TRACE_ID = "a".repeat(32);
    private static final String SPAN_ID = "b".repeat(16);

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clearProperties() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
    }

    private static final String TRACE_STATE = "vendor=t61rcWkgMzE,other=1";

    @Test
    void inboundExtractionAndOutboundInjectionCarryTheSameTrace() throws Exception {
        try (AppRuntime app = FreewayApp.of(new TraceModule(), new HttpModule()).add(CloudModules.standard()).start()) {
            app.get(ServiceRegistry.class).register(
                ServiceInstance.of("svc", "i1",
                    Endpoint.of("http", "127.0.0.1", app.get(com.jujin.freeway.http.WebServer.class).port()),
                    Map.of()));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + app.get(com.jujin.freeway.http.WebServer.class).port()
                        + "/api/call"))
                .header("traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-01")
                .header("tracestate", TRACE_STATE)
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());

            // Inbound: the request runs under a server span that continues the
            // caller's trace — same traceId, the caller's span as its parent,
            // and a span id of its own (the callee hop, not a copy).
            TraceContext entry = TraceModule.ENTRY_SEEN.get();
            assertEquals(TRACE_ID, entry.traceId());
            assertEquals(SPAN_ID, entry.parentSpanId());
            assertNotEquals(SPAN_ID, entry.spanId());
            assertEquals(TRACE_STATE, entry.traceState(),
                "vendor trace state must survive the hop, not be dropped");

            // Outbound: the callee handler saw the same trace id (re-injected).
            TraceContext callee = TraceModule.CALLEE_SEEN.get();
            assertEquals(TRACE_ID, callee.traceId());
            assertEquals(entry.spanId(), callee.parentSpanId(),
                "the outbound call is a child of the server span");
            assertEquals(TRACE_STATE, callee.traceState());
        }
    }

    static class TraceModule implements ModuleEx {
        static final AtomicReference<TraceContext> ENTRY_SEEN = new AtomicReference<>();
        static final AtomicReference<TraceContext> CALLEE_SEEN = new AtomicReference<>();

        @Override
        public void bind(Binder b) {
            b.contribute(Route.class).add(Route.get("/api/call", CallHandler.class));
            b.contribute(Route.class).add(Route.get("/api/echo", ctx -> {
                CALLEE_SEEN.set(InvocationContext.current().map(InvocationContext::trace).orElse(null));
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
                ENTRY_SEEN.set(InvocationContext.current().map(InvocationContext::trace).orElse(null));
                client.call("svc", CloudRequest.get("/api/echo"));
                ctx.send(200, "ok");
            }
        }
    }
}
