package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.HealthCheck;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.RequestTimingFilter;
import com.jujin.freeway.http.route.RouteHandler;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * JMH benchmark for the real Freeway HTTP filter chain.
 *
 * <p>Builds the same filter chain used in production:
 * {@link RequestTimingFilter} &rarr; {@link CorsFilter} &rarr;
 * {@link HealthFilter} &rarr; no-op route handler.
 *
 * <p>Uses a real {@link FreewayHttpContext} (not a stub) so that filter
 * overhead includes real header/body/status operations.
 *
 * <p>Three request shapes exercise different filter code paths:
 * <ul>
 *   <li>{@link #normalRequest()} — passes through all filters to the handler</li>
 *   <li>{@link #healthCheckMatch()} — intercepted by HealthFilter before routing</li>
 *   <li>{@link #corsPreflight()} — intercepted by CorsFilter as a CORS preflight</li>
 * </ul>
 */
@State(Scope.Benchmark)
public class FilterChainBenchmark {

    private FreewayHttpContext normalCtx;
    private FreewayHttpContext healthCtx;
    private FreewayHttpContext corsCtx;
    private RouteHandler chain;

    @Setup
    public void setup() {
        var json = new JsonCodecDefault();
        var coercer = new CoercerDefault();

        // Real filter chain: timing -> cors -> health -> noop handler
        var timing = new RequestTimingFilter();
        var cors = CorsFilter.DEFAULT;
        var health = new HealthFilter(true, "/healthz", new HealthCheck.Default());
        RouteHandler noop = ctx -> {};
        RouteHandler h = ctx -> health.doFilter(ctx, noop);
        RouteHandler c = ctx -> cors.doFilter(ctx, h);
        chain = ctx -> timing.doFilter(ctx, c);

        // Normal GET /ping — passes through all filters
        normalCtx = new FreewayHttpContext(json, coercer);
        normalCtx.reset("GET", "/ping", null, Map.of("Host", List.of("127.0.0.1")),
            InputStream.nullInputStream(), -1, false,
            OutputStream.nullOutputStream(), null, false, true);

        // Health check request — intercepted by HealthFilter
        healthCtx = new FreewayHttpContext(json, coercer);
        healthCtx.reset("GET", "/healthz", null, Map.of("Host", List.of("127.0.0.1")),
            InputStream.nullInputStream(), -1, false,
            OutputStream.nullOutputStream(), null, false, true);

        // CORS preflight — intercepted by CorsFilter
        corsCtx = new FreewayHttpContext(json, coercer);
        corsCtx.reset("OPTIONS", "/api/data", null,
            Map.ofEntries(
                Map.entry("Host", List.of("127.0.0.1")),
                Map.entry("Origin", List.of("https://example.com")),
                Map.entry("Access-Control-Request-Method", List.of("POST"))
            ),
            InputStream.nullInputStream(), -1, false,
            OutputStream.nullOutputStream(), null, false, true);
    }

    /** Request passes through all filters to the handler. */
    @Benchmark
    public void normalRequest() throws Exception {
        chain.handle(normalCtx);
    }

    /** HealthFilter matches /healthz and short-circuits with 200 + JSON. */
    @Benchmark
    public void healthCheckMatch() throws Exception {
        chain.handle(healthCtx);
    }

    /** CorsFilter intercepts OPTIONS + Access-Control-Request-Method as preflight. */
    @Benchmark
    public void corsPreflight() throws Exception {
        chain.handle(corsCtx);
    }
}
