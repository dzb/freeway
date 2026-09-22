package com.jujin.freeway.cloud.observe;

import com.jujin.freeway.cloud.CloudModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.context.InvocationContext;
import com.jujin.freeway.cloud.context.TraceContext;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.http.HttpServer;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Inbound server spans: one per application request, none for the framework's
 * own probes and scrape endpoint. The request runs <em>inside</em> the span, so
 * the handler's view of the trace — and therefore every outbound call it makes
 * — continues the callee hop, and log lines carry its traceId.
 */
class InboundTracingTest {

    private static final String TRACE_ID = "c".repeat(32);
    private static final String SPAN_ID = "d".repeat(16);

    interface Seen {
        AtomicReference<TraceContext> CONTEXT = new AtomicReference<>();
        AtomicReference<String> MDC_TRACE_ID = new AtomicReference<>();
        AtomicReference<String> MDC_SPAN_ID = new AtomicReference<>();
    }

    @BeforeEach
    void randomPort() {
        System.setProperty(HttpConfigKeys.SERVER_PORT, "0");
    }

    @AfterEach
    void clear() {
        System.clearProperty(HttpConfigKeys.SERVER_PORT);
        Seen.CONTEXT.set(null);
        Seen.MDC_TRACE_ID.set(null);
        Seen.MDC_SPAN_ID.set(null);
    }

    /** Records the context and MDC as the handler sees them. */
    static class Handler implements com.jujin.freeway.http.route.RouteHandler {
        @Override
        public void handle(com.jujin.freeway.http.HttpContext ctx) throws Exception {
            Seen.CONTEXT.set(InvocationContext.current().map(InvocationContext::trace).orElse(null));
            Seen.MDC_TRACE_ID.set(MDC.get("traceId"));
            Seen.MDC_SPAN_ID.set(MDC.get("spanId"));
            ctx.send(200, "ok");
        }
    }

    @Test
    void requestRunsInsideTheServerSpanAndLogsCarryItsTrace() throws Exception {
        try (AppRuntime app = FreewayApp.create(new HttpModule()).add(CloudModule.class).add(new Routes()).start()) {
            assertEquals(200, get(app, "/api/thing", true).statusCode());

            TraceContext seen = Seen.CONTEXT.get();
            assertEquals(TRACE_ID, seen.traceId(), "same trace as the caller");
            assertEquals(SPAN_ID, seen.parentSpanId(), "the caller's span is the parent");
            assertNotEquals(SPAN_ID, seen.spanId(), "the callee hop has its own span id");

            // TracerDefault mirrors the active span into MDC: inbound logs are
            // joinable to the trace without the application doing anything.
            assertEquals(TRACE_ID, Seen.MDC_TRACE_ID.get());
            assertEquals(seen.spanId(), Seen.MDC_SPAN_ID.get());
        }
    }

    @Test
    void withoutAnInboundTraceTheRequestStillGetsATrace() throws Exception {
        try (AppRuntime app = FreewayApp.create(new HttpModule()).add(CloudModule.class).add(new Routes()).start()) {
            assertEquals(200, get(app, "/api/thing", false).statusCode());

            TraceContext seen = Seen.CONTEXT.get();
            assertTrue(seen != null && seen.traceId().length() == 32,
                "an untraced caller must not leave the request without a trace");
            assertEquals(Seen.MDC_TRACE_ID.get(), seen.traceId());
        }
    }

    @Test
    void probesAndScrapeEndpointAreNotTraced() throws Exception {
        RecordingTracer tracer = new RecordingTracer();
        try (AppRuntime app = FreewayApp.create(new HttpModule()).add(CloudModule.class).add(new Routes()).add(binder -> binder.bind(Tracer.class).to(container -> tracer).primary()).start()) {
            assertEquals(200, get(app, "/healthz", true).statusCode());
            assertEquals(200, get(app, "/health/ready", true).statusCode());
            assertEquals(200, get(app, "/metrics", true).statusCode());
            assertEquals(List.of(), tracer.names, "infrastructure traffic stays out of the trace");

            assertEquals(200, get(app, "/api/thing", true).statusCode());
            assertEquals(1, tracer.names.size());
            assertEquals("GET /api/thing", tracer.names.getFirst());
        }
    }

    /** A tracer that records span names and owns a child context, like the default one. */
    static final class RecordingTracer implements Tracer {
        final List<String> names = new CopyOnWriteArrayList<>();

        @Override
        public Span start(String name) {
            names.add(name);
            TraceContext parent = InvocationContext.current()
                .map(InvocationContext::trace).orElseGet(TraceContext::root);
            TraceContext child = parent.child();
            return new Span() {
                @Override public InvocationContext context() {
                    return InvocationContext.of(child, null, null);
                }

                @Override public void addTag(String key, String value) { }

                @Override public void addError(Throwable t) { }

                @Override public void close() { }
            };
        }

        @Override
        public Span start(String name, TraceContext parent) {
            return start(name);
        }
    }

    static class Routes implements ModuleEx {
        @Override
        public void bind(Binder binder) {
            binder.contribute(Route.class).add(Route.get("/api/thing", new Handler()));
        }
    }

    private static HttpResponse<String> get(AppRuntime app, String path, boolean traced) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
            URI.create("http://127.0.0.1:" + app.get(HttpServer.class).port() + path)).GET();
        if (traced) {
            builder.header("traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-01");
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
