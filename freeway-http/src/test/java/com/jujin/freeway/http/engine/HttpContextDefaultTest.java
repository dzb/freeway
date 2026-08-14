package com.jujin.freeway.http.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.sse.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            for (var entry : ctx.responseHeaderEntries()) {
                headHeaders.put(entry.getKey(), entry.getValue());
            }
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
        assertEquals(Map.of("X-Custom", "abc"), toMap(ctx),
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
        assertTrue(toMap(ctx).containsKey("Content-Type"),
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
    void bodylessStatusDropsHandlerSetContentLength() throws Exception {
        // RFC 9110 §8.6: a 204 must not carry Content-Length even when the
        // handler set one — the header must not leak onto the wire next to
        // a suppressed body.
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.status(204).setHeader("Content-Length", "999");
        ctx.output(new byte[0]);

        String wire = out.toString();
        assertFalse(wire.toLowerCase(Locale.ROOT).contains("content-length"),
            "204 must not carry Content-Length on the wire: " + wire);
    }

    @Test
    void notModifiedStatusDropsHandlerSetContentLength() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.status(304).setHeader("Content-Length", "999");
        ctx.output(new byte[0]);

        String wire = out.toString();
        assertFalse(wire.toLowerCase(Locale.ROOT).contains("content-length"),
            "304 must not carry Content-Length on the wire: " + wire);
    }

    @Test
    void bodyAllowedKeepsHandlerSetContentLength() throws Exception {
        // A body-allowed status keeps the handler's Content-Length verbatim.
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.setHeader("Content-Length", "5");
        ctx.output("hello".getBytes(StandardCharsets.UTF_8));

        String wire = out.toString();
        assertTrue(wire.contains("Content-Length: 5"),
            "200 must keep the handler-set Content-Length: " + wire);
        assertTrue(wire.endsWith("hello"), "body must be written: " + wire);
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
        assertEquals(1, toMap(ctx).size());
        assertEquals("2", toMap(ctx).get("content-length"));
    }

    @Test
    void rejectsOutOfRangeStatusCodes() {
        var ctx = context(new RecordingWriter());
        assertThrows(IllegalArgumentException.class, () -> ctx.status(0),
            "status(0) must be rejected — it would emit an invalid status line");
        assertThrows(IllegalArgumentException.class, () -> ctx.status(99),
            "status(99) must be rejected — below the 100 minimum");
        assertThrows(IllegalArgumentException.class, () -> ctx.status(600),
            "status(600) must be rejected — above the 599 maximum");
        assertThrows(IllegalArgumentException.class, () -> ctx.status(999999),
            "status(999999) must be rejected");
        assertThrows(IllegalArgumentException.class, () -> ctx.status(-1),
            "negative status codes must be rejected");
    }

    @Test
    void acceptsBoundaryStatusCodes() {
        var ctx = context(new RecordingWriter());
        ctx.status(100);
        assertEquals(100, ctx.status(), "100 is the lowest legal status code");
        ctx.status(599);
        assertEquals(599, ctx.status(), "599 is the highest legal status code");
    }

    @Test
    void setHeaderRejectsNonIso88591Value() {
        var ctx = context(new RecordingWriter());
        assertThrows(IllegalArgumentException.class, () ->
            ctx.setHeader("X-Filename", "报告.pdf"),
            "a UTF-8 value outside ISO-8859-1 must be rejected, not silently "
                + "mangled to '?' by the HTTP/1.1 writer");
        ctx.setHeader("X-Latin", "caf\u00e9");
        assertEquals("caf\u00e9", toMap(ctx).get("X-Latin"),
            "é (U+00E9) is inside ISO-8859-1 and must be accepted");
    }

    private static Map<String, String> toMap(HttpContextDefault ctx) {
        Map<String, String> map = new LinkedHashMap<>();
        for (var entry : ctx.responseHeaderEntries()) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
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

    @Test
    void keepAliveResetClearsPrincipalAttributesAndRollsCorrelationId() {
        var ctx = context(new RecordingWriter());
        ctx.reset("GET", "/one", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);
        ctx.setPrincipal("alice");
        ctx.setAttribute("cart", List.of("a", "b"));
        String firstId = ctx.correlationId();
        assertNotNull(firstId);

        // Second request on the same keep-alive connection, no X-Request-Id.
        ctx.reset("GET", "/two", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        assertNull(ctx.principal(),
            "principal must not leak to the next keep-alive request");
        assertNull(ctx.attribute("cart"),
            "attributes must not leak to the next keep-alive request");
        assertTrue(ctx.attributes().isEmpty(),
            "attributes() must be empty after a reset");
        assertNotEquals(firstId, ctx.correlationId(),
            "a request without X-Request-Id must get a fresh correlation id");
    }

    @Test
    void keepAliveResetHonorsIncomingCorrelationId() {
        var ctx = context(new RecordingWriter());
        ctx.reset("GET", "/one", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), "req-1", false, false);
        ctx.setPrincipal("alice");
        ctx.setAttribute("k", "v");

        ctx.reset("GET", "/two", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), "req-2", false, false);

        assertEquals("req-2", ctx.correlationId(),
            "an incoming X-Request-Id must be applied after the reset");
        assertNull(ctx.principal());
        assertNull(ctx.attribute("k"));
    }

    @Test
    void keepAliveResetRefreshesStartTime() throws Exception {
        var ctx = context(new RecordingWriter());
        ctx.reset("GET", "/one", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);
        Instant first = ctx.startTime();

        Thread.sleep(10);
        ctx.reset("GET", "/two", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        assertTrue(ctx.startTime().isAfter(first),
            "startTime must be refreshed per request so timing stats stay accurate");
    }

    @Test
    void headOutputFileSkipsSendfileTransfer() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("HEAD", "/big.bin", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);
        Path file = Files.createTempFile("head-sendfile", ".bin");
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ)) {
            boolean[] transferred = {false};
            ctx.setFileSender((ch, offset, length) -> transferred[0] = true);

            ctx.outputFile(channel, 0, 64 * 1024);

            assertFalse(transferred[0],
                "HEAD must never transfer file bytes on the sendfile path");
            assertEquals("65536", toMap(ctx).get("Content-Length"),
                "HEAD must report the same Content-Length as GET");
            assertTrue(ctx.isResponded());
            assertTrue(writer.ended, "end must still be called for HEAD");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void getOutputFileStillUsesSendfileTransfer() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/big.bin", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);
        Path file = Files.createTempFile("get-sendfile", ".bin");
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ)) {
            boolean[] transferred = {false};
            ctx.setFileSender((ch, offset, length) -> transferred[0] = true);

            ctx.outputFile(channel, 0, 64 * 1024);

            assertTrue(transferred[0],
                "GET must keep using the sendfile transfer path");
            assertEquals("65536", toMap(ctx).get("Content-Length"));
            assertTrue(writer.ended);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
