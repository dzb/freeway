package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Setup;

/**
 * JMH benchmark for {@link FreewayHttpContext} response output paths.
 *
 * <p>Covers plain-text, JSON, and not-found responses as well as
 * request-body reading combined with output and the {@code sendJson}
 * convenience shortcut.
 */
@State(Scope.Thread)
public class HttpContextOutputBenchmark {

    private static final JsonCodec JSON = new JsonCodecDefault();
    private static final Coercer COERCER = new CoercerDefault();
    private static final byte[] PONG = "pong".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LARGE_BODY = "large-body-".repeat(2000).getBytes(StandardCharsets.UTF_8);

    private static final Map<String, List<String>> REQUEST_HEADERS;
    static {
        REQUEST_HEADERS = new LinkedHashMap<>();
        REQUEST_HEADERS.put("Host", List.of("127.0.0.1"));
        REQUEST_HEADERS.put("Connection", List.of("keep-alive"));
        REQUEST_HEADERS.put("User-Agent", List.of("freeway-bench"));
        REQUEST_HEADERS.put("Accept", List.of("*/*"));
    }

    private FreewayHttpContext ctx;
    private OutputStream sink;
    private FreewayHttpContext bodyReadCtx;
    private FreewayHttpContext sendJsonCtx;

    @Setup
    public void setup() {
        sink = OutputStream.nullOutputStream();
        ctx = new FreewayHttpContext(JSON, COERCER);

        // Context pre-configured for body-read scenario (POST with body)
        bodyReadCtx = new FreewayHttpContext(JSON, COERCER);
        bodyReadCtx.reset("POST", "/api/data", null, REQUEST_HEADERS,
            new ByteArrayInputStream(LARGE_BODY), LARGE_BODY.length, false,
            sink, null, false, true);

        // Context pre-configured for sendJson convenience shortcut
        sendJsonCtx = new FreewayHttpContext(JSON, COERCER);
        sendJsonCtx.reset("GET", "/api/resource", null, REQUEST_HEADERS,
            InputStream.nullInputStream(), -1, false,
            sink, null, false, true);
    }

    // --- Existing scenarios (preserved) ---

    @Benchmark
    public HttpContext sendPongText() throws IOException {
        ctx.reset("GET", "/ping", null, REQUEST_HEADERS,
            InputStream.nullInputStream(), -1, false,
            sink, null, false, true);
        ctx.status(200);
        ctx.headerSet("Content-Type", "text/plain; charset=utf-8");
        return ctx.output(PONG);
    }

    @Benchmark
    public HttpContext sendPongJson() throws IOException {
        ctx.reset("GET", "/ping", null, REQUEST_HEADERS,
            InputStream.nullInputStream(), -1, false,
            sink, null, false, true);
        ctx.status(200);
        return ctx.outputJson(Map.of("status", "ok"));
    }

    @Benchmark
    public HttpContext sendNotFound() throws IOException {
        ctx.reset("GET", "/missing", null, REQUEST_HEADERS,
            InputStream.nullInputStream(), -1, false,
            sink, null, false, true);
        ctx.status(404);
        return ctx.output("Not Found".getBytes(StandardCharsets.UTF_8));
    }

    // --- New scenarios ---

    /** Read request body then output response — simulates POST handler. */
    @Benchmark
    public HttpContext readBodyThenOutput() throws IOException {
        bodyReadCtx.body();                 // reads + caches body
        bodyReadCtx.status(201);
        bodyReadCtx.headerSet("Content-Type", "application/json");
        return bodyReadCtx.output(PONG);
    }

    /** Convenience shortcut: sendJson(status, value). */
    @Benchmark
    public HttpContext sendJsonShortcut() throws IOException {
        sendJsonCtx.sendJson(200, Map.of("id", 42, "name", "Alice"));
        return sendJsonCtx;
    }
}
