package com.jujin.freeway.http.engine;
import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.io.IOException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jujin.freeway.http.sse.SseEmitter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpContextDefaultTest {

    private static final JsonCodec CODEC =
            new JsonCodecDefault();
    private static final Coercer COERCER =
            new CoercerDefault();

    /** Records writer calls without needing a real transport. */
    private static final class RecordingWriter implements HttpResponseWriter {
        int headStatus;
        final Map<String, String> headHeaders = new LinkedHashMap<>();
        final List<byte[]> bodies = new ArrayList<>();
        boolean ended;
        SseEmitter sse;

        @Override
        public void writeHead(HttpContextDefault ctx) {
            headStatus = ctx.status();
            headHeaders.putAll(ctx.responseHeaders());
        }

        @Override
        public void writeBody(HttpContextDefault ctx, byte[] data) {
            bodies.add(data);
        }

        @Override
        public void end(HttpContextDefault ctx) {
            ended = true;
        }

        @Override
        public SseEmitter openSse(HttpContextDefault ctx) throws IOException {
            sse = new SseEmitter(new ByteArrayOutputStream());
            return sse;
        }
    }

    private static HttpContextDefault context(RecordingWriter writer) {
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.setWriter(writer);
        return ctx;
    }

    @Test
    void outputOrchestratesWriterHeadBodyEnd() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        ctx.send(200, "hello");

        assertEquals(200, writer.headStatus, "head must carry the status");
        assertFalse(writer.bodies.isEmpty(), "body must be written");
        assertEquals("hello", new String(writer.bodies.getLast()));
        assertTrue(writer.ended, "end must be called after output");
    }

    @Test
    void setHeaderIsSingleSourceOfTruth() {
        var ctx = context(new RecordingWriter());
        ctx.setHeader("X-Custom", "abc");
        assertEquals(Map.of("X-Custom", "abc"), ctx.responseHeaders(),
                "setHeader must write the context's response headers");
    }

    @Test
    void writeHeadConsumesResponseHeaders() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        ctx.status(201);
        ctx.setHeader("X-Custom", "abc");
        ctx.send(201, "created");

        assertEquals(201, writer.headStatus);
        assertEquals("abc", writer.headHeaders.get("X-Custom"),
                "writeHead must see the response headers set on the context");
    }

    @Test
    void setHeaderRejectsCRLFInName() {
        var ctx = context(new RecordingWriter());
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X-Test\r\nInjected", "yes"),
                "Header name with CRLF must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X:Test", "yes"),
                "Header name with colon must be rejected");
    }

    @Test
    void sseOpensOnWriter() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        try (var emitter = ctx.sse()) {
            emitter.send("ok");
        }

        assertTrue(writer.sse != null, "sse() must open the emitter via the writer");
        assertTrue(ctx.responseHeaders().containsKey("Content-Type"),
                "SSE Content-Type must be set on the context");
    }

    @Test
    void noBodyStatusStillEndsResponse() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("POST", "/", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        ctx.status(204);
        ctx.output("should-be-ignored".getBytes());

        assertEquals(204, writer.headStatus);
        assertEquals(1, writer.bodies.size(), "writeBody is called once with the data");
        assertTrue(writer.ended);
        assertFalse(ctx.allowsResponseBody(), "204 must not allow a body");
    }

    @Test
    void defaultsToHttp1Writer() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.send(200, "ok");

        String wire = out.toString();
        assertTrue(wire.startsWith("HTTP/1.1 200"), "default writer must emit HTTP/1.1: " + wire);
        assertTrue(wire.contains("Content-Length: 2"));
        assertTrue(wire.endsWith("ok"), "body must be written: " + wire);
    }

    @Test
    void queryParamsAreDeeplyUnmodifiable() throws Exception {
        var ctx = context(new RecordingWriter());
        ctx.reset("GET", "/path", "a=1&a=2", Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        Map<String, List<String>> params = ctx.queryParams();
        assertThrows(UnsupportedOperationException.class, () ->
            params.put("b", List.of("3")));
        assertThrows(UnsupportedOperationException.class, () ->
            params.get("a").add("3"));
    }

    @Test
    void repeatedOutputIsIgnoredAfterResponse() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        ctx.send(200, "first");
        ctx.send(200, "second");

        assertEquals(1, writer.bodies.size(),
            "a second output after the response must be a no-op");
        assertEquals("first", new String(writer.bodies.getFirst()));
    }

    @Test
    void responseHeadersAreCaseInsensitive() {
        var ctx = context(new RecordingWriter());
        ctx.setHeader("Content-Length", "1");
        ctx.setHeader("content-length", "2");
        assertEquals(1, ctx.responseHeaders().size());
        assertEquals("2", ctx.responseHeaders().get("content-length"));
    }

    @Test
    void noEntityStatusesAreNotCompressed() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/", null,
            Map.of("accept-encoding", List.of("gzip")), null, -1, false,
            new ByteArrayOutputStream(), null, false, false);
        ctx.status(204).setHeader("Content-Type", "text/plain");
        ctx.output(new byte[512]);
        assertFalse(writer.headHeaders.containsKey("Content-Encoding"));
    }
}
