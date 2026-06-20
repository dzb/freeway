package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.route.RouteHandler;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Setup;

@State(Scope.Benchmark)
public class FilterChainBenchmark {

    private RouteHandler chain;
    private HttpContext ctx;

    @Setup
    public void setup() {
        // Build the typical freeway filter chain:
        // RequestTimingFilter → CorsFilter → HealthFilter → dispatch handler

        var timing = new RequestTimingFilter();
        var cors = CorsFilter.DEFAULT;
        var health = new HealthFilter(true, "/healthz", new HealthCheck.Default());

        // Chain: timing → cors → health → noop handler
        RouteHandler noop = ctx -> {};
        RouteHandler h = ctx -> health.doFilter(ctx, noop);
        RouteHandler c = ctx -> cors.doFilter(ctx, h);
        chain = ctx -> timing.doFilter(ctx, c);

        // Minimal HttpContext that returns expected values
        ctx = new StubContext();
    }

    @Benchmark
    public void fullChain() throws Exception {
        chain.handle(ctx);
    }

    /**
     * Minimal HttpContext stub — avoids FreewayHttpContext overhead to isolate filter cost.
     */
    private static final class StubContext extends HttpContext {

        private final Map<String, String> responseHeaders = new LinkedHashMap<>();
        private final RequestContext requestContext = new RequestContext() {
            @Override public String correlationId() { return "bench"; }
            @Override public Instant startTime() { return Instant.EPOCH; }
            @Override public Object principal() { return null; }
            @Override public void setPrincipal(Object p) {}
            @Override public Object attribute(String k) { return null; }
            @Override public void setAttribute(String k, Object v) {}
            @Override public Map<String, Object> attributes() { return Map.of(); }
        };
        private int status = 200;

        StubContext() {
            super(null, null);
        }

        // simulate typical bench request
        @Override public String method() { return "GET"; }
        @Override public String path() { return "/ping"; }

        // headers — returns null for everything (no Origin, no special headers)
        @Override public String header(String name) { return null; }
        @Override public List<String> headers(String name) { return List.of(); }

        // query params — no query string
        @Override public String queryParam(String name) { return null; }
        @Override public List<String> queryParams(String name) { return List.of(); }
        @Override public Map<String, List<String>> queryParams() { return Map.of(); }

        // body — no body
        @Override public byte[] body() throws IOException { return new byte[0]; }

        // response
        @Override public HttpContext status(int status) { this.status = status; return this; }
        @Override public int status() { return status; }
        @Override public HttpContext headerSet(String name, String value) {
            responseHeaders.put(name, value); return this;
        }
        @Override public HttpContext output(byte[] data) throws IOException {
            // noop — just mark as responded
            return this;
        }
        @Override public com.jujin.freeway.http.sse.SseEmitter sse() throws IOException {
            throw new UnsupportedOperationException();
        }
        @Override public RequestContext requestContext() {
            return requestContext;
        }
    }
}
