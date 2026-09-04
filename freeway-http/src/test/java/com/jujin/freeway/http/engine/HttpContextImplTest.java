package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

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

class HttpContextImplTest {

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
        public void writeHead(HttpContextImpl ctx) {
            headStatus = ctx.status();
            for (var entry : ctx.responseHeaderEntries()) {
                headHeaders.put(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public void writeBody(HttpContextImpl ctx, byte[] data) {
            bodies.add(data);
        }

        @Override
        public void end(HttpContextImpl ctx) {
            ended = true;
        }

        @Override
        public SseEmitter openSse(HttpContextImpl ctx) throws IOException {
            sse = new SseEmitter(new ByteArrayOutputStream());
            return sse;
        }
    }

    private static HttpContextImpl context(RecordingWriter writer) {
        var ctx = new HttpContextImpl(CODEC, COERCER);
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

        ctx.setStatus(201);
        ctx.setHeader("X-Custom", "abc");
        ctx.send(201, "created");

        assertEquals(201, writer.headStatus);
        assertEquals("abc", writer.headHeaders.get("X-Custom"),
                "writeHead must see the response headers set on the context");
    }

    @Test
    void responseIncludesDateHeader() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);

        ctx.send(200, "ok");

        assertTrue(writer.headHeaders.containsKey("Date"),
                "responses must carry an RFC 7231 Date header");
    }

    @Test
    void appSetDateHeaderIsNotOverridden() throws Exception {
        var writer = new RecordingWriter();
        var ctx = context(writer);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false,
                new ByteArrayOutputStream(), null, false, false);
        ctx.setHeader("Date", "custom-date");

        ctx.send(200, "ok");

        assertEquals("custom-date", writer.headHeaders.get("Date"),
                "an application-provided Date must win");
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

        ctx.setStatus(204);
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
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.setStatus(204).setHeader("Content-Length", "999");
        ctx.output(new byte[0]);

        String wire = out.toString();
        assertFalse(wire.toLowerCase(Locale.ROOT).contains("content-length"),
            "204 must not carry Content-Length on the wire: " + wire);
    }

    @Test
    void notModifiedStatusDropsHandlerSetContentLength() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.setStatus(304).setHeader("Content-Length", "999");
        ctx.output(new byte[0]);

        String wire = out.toString();
        assertFalse(wire.toLowerCase(Locale.ROOT).contains("content-length"),
            "304 must not carry Content-Length on the wire: " + wire);
    }

    @Test
    void bodyAllowedKeepsHandlerSetContentLength() throws Exception {
        // A body-allowed status keeps the handler's Content-Length verbatim.
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
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
        var ctx = new HttpContextImpl(CODEC, COERCER);
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
    void addHeaderPreservesMultipleValues() {
        var ctx = context(new RecordingWriter());
        ctx.addHeader("Set-Cookie", "a=1");
        ctx.addHeader("Set-Cookie", "b=2");

        List<String> values = new ArrayList<>();
        for (var entry : ctx.responseHeaderEntries()) {
            if ("Set-Cookie".equals(entry.getKey())) {
                values.add(entry.getValue());
            }
        }
        assertEquals(List.of("a=1", "b=2"), values);
    }

    @Test
    void setHeaderReplacesAllValuesForName() {
        var ctx = context(new RecordingWriter());
        ctx.addHeader("X-Multi", "1");
        ctx.addHeader("X-Multi", "2");
        ctx.setHeader("X-Multi", "3");

        assertEquals(Map.of("X-Multi", "3"), toMap(ctx));
    }

    @Test
    void http1WriterEmitsMultipleSetCookieLines() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);
        ctx.addHeader("Set-Cookie", "a=1");
        ctx.addHeader("Set-Cookie", "b=2");
        ctx.send(200, "ok");

        String wire = out.toString();
        assertTrue(wire.contains("Set-Cookie: a=1\r\n"));
        assertTrue(wire.contains("Set-Cookie: b=2\r\n"));
    }

    @Test
    void rejectsOutOfRangeStatusCodes() {
        var ctx = context(new RecordingWriter());
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(0),
            "setStatus(0) must be rejected — it would emit an invalid status line");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(99),
            "setStatus(99) must be rejected — below the 100 minimum");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(600),
            "setStatus(600) must be rejected — above the 599 maximum");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(999999),
            "setStatus(999999) must be rejected");
        assertThrows(IllegalArgumentException.class, () -> ctx.setStatus(-1),
            "negative status codes must be rejected");
    }

    @Test
    void acceptsBoundaryStatusCodes() {
        var ctx = context(new RecordingWriter());
        ctx.setStatus(100);
        assertEquals(100, ctx.status(), "100 is the lowest legal status code");
        ctx.setStatus(599);
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

    private static Map<String, String> toMap(HttpContextImpl ctx) {
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
        ctx.setStatus(204).setHeader("Content-Type", "text/plain");
        ctx.output(new byte[512]);
        assertFalse(writer.headHeaders.containsKey("Content-Encoding"));
    }

    @Test
    void http1StreamingKnownLengthUsesContentLength() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, 0, false, out, null, false, false);

        ctx.output(new ByteArrayInputStream("hello".getBytes()), 5);

        String wire = out.toString();
        assertTrue(wire.contains("Content-Length: 5"));
        assertFalse(wire.contains("Transfer-Encoding"));
        assertTrue(wire.endsWith("hello"));
    }

    @Test
    void http1StreamingUnknownLengthUsesChunked() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, 0, false, out, null, false, false);

        ctx.output(new ByteArrayInputStream("hello".getBytes()), -1);

        String wire = out.toString();
        assertTrue(wire.contains("Transfer-Encoding: chunked"));
        assertTrue(wire.endsWith("5\r\nhello\r\n0\r\n\r\n"));
    }

    @Test
    void http1BufferedGzipRoundTrips() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("POST", "/", null,
            Map.of("accept-encoding", List.of("gzip")), null, 0, false,
            out, null, false, false);
        ctx.setHeader("Content-Type", "text/plain");
        String original = "hello gzip ".repeat(64);
        ctx.output(original.getBytes(StandardCharsets.UTF_8));

        String wire = out.toString();
        assertTrue(wire.contains("Content-Encoding: gzip"));
        assertTrue(wire.contains("Content-Length: "));
        assertFalse(wire.contains("Transfer-Encoding"));

        byte[] gz = bodyAfterHeaders(out);
        try (var gin = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            assertEquals(original, new String(gin.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void http1StreamingGzipUsesChunked() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("POST", "/", null,
            Map.of("accept-encoding", List.of("gzip")), null, 0, false,
            out, null, false, false);
        ctx.setHeader("Content-Type", "text/plain");
        byte[] body = "stream ".repeat(64).getBytes(StandardCharsets.UTF_8);

        ctx.output(new ByteArrayInputStream(body), body.length);

        String wire = out.toString();
        assertTrue(wire.contains("Content-Encoding: gzip"));
        assertTrue(wire.contains("Transfer-Encoding: chunked"));
        assertFalse(wire.contains("Content-Length"));
    }

    @Test
    void http1HeadSuppressesBodyButKeepsLength() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextImpl(CODEC, COERCER);
        ctx.reset("HEAD", "/", null, Map.of(), null, 0, false, out, null, false, false);

        ctx.send(200, "hello");

        String wire = out.toString();
        assertTrue(wire.contains("Content-Length: 5"));
        assertFalse(wire.contains("hello"));
    }

    @Test
    void outputFileUsesSendfileForLargeFile() throws Exception {
        Path file = Files.createTempFile("sendfile", ".bin");
        try {
            int size = 64 * 1024;
            Files.write(file, new byte[size]);
            var out = new ByteArrayOutputStream();
            var ctx = new HttpContextImpl(CODEC, COERCER);
            long[] transferred = new long[2];
            ctx.setFileSender((channel, offset, length) -> {
                transferred[0] = offset;
                transferred[1] = length;
            });
            ctx.reset("GET", "/", null, Map.of(), null, 0, false, out, null, false, false);

            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                ctx.outputFile(channel, 0, size);
            }

            assertEquals(0L, transferred[0]);
            assertEquals((long) size, transferred[1]);
            assertTrue(out.toString().contains("Content-Length: " + size));
        } finally {
            Files.deleteIfExists(file);
        }
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
    void outputFileStreamsSmallFileThroughBuffer() throws Exception {
        Path file = Files.createTempFile("small", ".bin");
        try {
            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
            Files.write(file, content);
            var out = new ByteArrayOutputStream();
            var ctx = new HttpContextImpl(CODEC, COERCER);
            ctx.setFileSender((c, o, l) -> {
                throw new AssertionError("small file must not use sendfile");
            });
            ctx.reset("GET", "/", null, Map.of(), null, 0, false, out, null, false, false);

            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                ctx.outputFile(channel, 0, content.length);
            }

            String wire = out.toString();
            assertTrue(wire.contains("Content-Length: 5"));
            assertTrue(wire.endsWith("hello"));
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

    private static byte[] bodyAfterHeaders(ByteArrayOutputStream out) {
        byte[] wire = out.toByteArray();
        for (int i = 0; i + 3 < wire.length; i++) {
            if (wire[i] == '\r' && wire[i + 1] == '\n'
                    && wire[i + 2] == '\r' && wire[i + 3] == '\n') {
                return Arrays.copyOfRange(wire, i + 4, wire.length);
            }
        }
        return new byte[0];
    }
}
