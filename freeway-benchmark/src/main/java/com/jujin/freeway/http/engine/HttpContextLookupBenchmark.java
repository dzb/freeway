package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * JMH benchmark for {@link FreewayHttpContext} lookup methods.
 *
 * <p>Uses a real {@link FreewayHttpContext} populated via {@code reset()}
 * with realistic request headers and query parameters. This replaces the
 * earlier custom {@code BenchContext} stub that did not reflect the real
 * O(1)-then-O(n) header lookup strategy.
 */
@State(Scope.Thread)
public class HttpContextLookupBenchmark {

    private static final Map<String, List<String>> REQUEST_HEADERS = Map.ofEntries(
        Map.entry("X-Trace-Id", List.of("trace-1")),
        Map.entry("Accept", List.of("text/plain")),
        Map.entry("Content-Type", List.of("application/json")),
        Map.entry("Cache-Control", List.of("no-cache")),
        Map.entry("User-Agent", List.of("freeway-bench")),
        Map.entry("X-Request-Id", List.of("req-1")),
        Map.entry("X-Forwarded-For", List.of("127.0.0.1")),
        Map.entry("Authorization", List.of("Bearer token"))
    );

    private FreewayHttpContext ctx;

    @Setup
    public void setup() {
        ctx = new FreewayHttpContext(new JsonCodecDefault(), new CoercerDefault());
        ctx.reset("GET", "/users/42", "page=3&q=freeway",
            REQUEST_HEADERS,
            InputStream.nullInputStream(), -1, false,
            OutputStream.nullOutputStream(), null, false, true);
        ctx.pathVars(Map.of("id", "42"));
    }

    /** O(1) exact-match query param lookup (LinkedHashMap.get). */
    @Benchmark
    public String queryParam() {
        return ctx.queryParam("page");
    }

    /** O(1) exact-match header lookup. */
    @Benchmark
    public String headerExactMatch() {
        return ctx.header("X-Trace-Id");
    }

    /** Exact miss, then O(n) case-insensitive fallback scan. */
    @Benchmark
    public String headerCaseInsensitive() {
        return ctx.header("x-trace-id");
    }

    /** Full miss: O(1) miss + O(n) case-insensitive scan. */
    @Benchmark
    public String headerMiss() {
        return ctx.header("x-missing-header");
    }

    /** queryParam fallback to pathVar via {@link #param(String)}. */
    @Benchmark
    public String paramLookup() {
        return ctx.param("id");
    }
}
