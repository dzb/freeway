package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Setup;

@State(Scope.Thread)
public class HttpContextOutputBenchmark {

    private static final JsonCodec JSON = new JsonCodecDefault();
    private static final Coercer COERCER = new CoercerDefault();
    private static final byte[] BODY = "pong".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    // Minimal headers for a simple GET /ping → 200 pong response
    private static Map<String, List<String>> requestHeaders;
    static {
        requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Host", List.of("127.0.0.1"));
        requestHeaders.put("Connection", List.of("keep-alive"));
        requestHeaders.put("User-Agent", List.of("freeway-bench"));
        requestHeaders.put("Accept", List.of("*/*"));
    }

    private FreewayHttpContext ctx;
    private OutputStream sink;

    @Setup
    public void setup() {
        // Black hole output — write to /dev/null
        sink = OutputStream.nullOutputStream();
        ctx = new FreewayHttpContext(JSON, COERCER);
    }

    @Benchmark
    public HttpContext sendPongText() throws IOException {
        ctx.reset("GET", "/ping", null, requestHeaders,
            InputStream.nullInputStream(), -1, false,
            sink, null, false, true);
        ctx.status(200);
        ctx.headerSet("Content-Type", "text/plain; charset=utf-8");
        return ctx.output(BODY);
    }

    @Benchmark
    public HttpContext sendPongJson() throws IOException {
        ctx.reset("GET", "/ping", null, requestHeaders,
            InputStream.nullInputStream(), -1, false,
            sink, null, false, true);
        ctx.status(200);
        return ctx.outputJson(Map.of("status", "ok"));
    }

    @Benchmark
    public HttpContext sendNotFound() throws IOException {
        ctx.reset("GET", "/missing", null, requestHeaders,
            InputStream.nullInputStream(), -1, false,
            sink, null, false, true);
        ctx.status(404);
        return ctx.output("Not Found".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
