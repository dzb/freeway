package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.HttpExchangeSupport;
import com.jujin.freeway.http.HttpServerConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire behaviour of the built-in HTTP/1.1 transport: framing derived from
 * the committed {@code ResponseHead} (Content-Length / chunked /
 * Connection), gzip execution, HEAD suppression, and the sendfile path.
 * Policy (status, headers, gzip decision) is asserted separately in
 * {@code HttpExchangeSupportTest}.
 */
class Http1xTransportTest {

    private static final HttpServerConfig.CompressionConfig COMPRESSION =
        HttpServerConfig.CompressionConfig.DEFAULT;

    /** A support object whose transport writes onto {@code out}. */
    private static HttpExchangeSupport exchange(String method,
            Map<String, List<String>> headers, ByteArrayOutputStream out) {
        var transport = new HttpTransportImpl(method, "/", null, headers,
            new ByteArrayInputStream(new byte[0]), -1, false, out, true,
            false, null, "");
        return new HttpExchangeSupport(transport, new JsonCodecDefault(),
            new CoercerDefault(), HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
            COMPRESSION, null);
    }

    private static String wire(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bodyAfterHeaders(ByteArrayOutputStream out) {
        byte[] raw = out.toByteArray();
        for (int i = 0; i + 3 < raw.length; i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n'
                    && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                return Arrays.copyOfRange(raw, i + 4, raw.length);
            }
        }
        return new byte[0];
    }

    // -- framing --

    @Test
    void defaultsToHttp1Wire() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        ctx.sendText(200, "ok");

        String text = wire(out);
        assertTrue(text.startsWith("HTTP/1.1 200"), "default writer must emit HTTP/1.1: " + text);
        assertTrue(text.contains("Content-Length: 2"));
        assertTrue(text.endsWith("ok"), "body must be written: " + text);
        assertFalse(text.contains("Transfer-Encoding"),
            "a known length must not be framed chunked");
    }

    @Test
    void bodylessStatusDropsHandlerSetContentLengthOnTheWire() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        ctx.setStatus(204).setHeader("Content-Length", "999");
        ctx.body(new byte[0]);

        String text = wire(out);
        assertTrue(text.startsWith("HTTP/1.1 204"),
            "a 204 must be committed with its status line: " + text);
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("content-length"),
            "204 must not carry Content-Length on the wire: " + text);
    }

    @Test
    void notModifiedStatusDropsHandlerSetContentLengthOnTheWire() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        ctx.setStatus(304).setHeader("Content-Length", "999");
        ctx.body(new byte[0]);

        String text = wire(out);
        assertTrue(text.startsWith("HTTP/1.1 304"),
            "a 304 must be committed with its status line: " + text);
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("content-length"),
            "304 must not carry Content-Length on the wire: " + text);
    }

    @Test
    void resetContentStatusDropsContentLengthOnTheWire() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        ctx.setStatus(205).setHeader("Content-Length", "999");
        ctx.body(new byte[0]);

        String text = wire(out);
        assertTrue(text.startsWith("HTTP/1.1 205"), "205 must be committed: " + text);
        assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("content-length"),
            "205 must not carry Content-Length: " + text);
    }

    @Test
    void http1HeadOfUnknownLengthUsesChunkedWithoutABody() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("HEAD", Map.of(), out);

        ctx.body(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), -1);

        String text = wire(out);
        assertTrue(text.contains("Transfer-Encoding: chunked"));
        assertFalse(text.contains("Content-Length"));
        assertFalse(text.contains("hello"), "HEAD carries no body bytes: " + text);
    }

    @Test
    void http1WriterEmitsMultipleSetCookieLines() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);
        ctx.addHeader("Set-Cookie", "a=1");
        ctx.addHeader("Set-Cookie", "b=2");
        ctx.sendText(200, "ok");

        String text = wire(out);
        assertTrue(text.contains("Set-Cookie: a=1\r\n"));
        assertTrue(text.contains("Set-Cookie: b=2\r\n"));
    }

    @Test
    void http1StreamingKnownLengthUsesContentLength() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        ctx.body(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), 5);

        String text = wire(out);
        assertTrue(text.contains("Content-Length: 5"));
        assertFalse(text.contains("Transfer-Encoding"));
        assertTrue(text.endsWith("hello"));
    }

    @Test
    void http1StreamingUnknownLengthUsesChunked() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        ctx.body(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), -1);

        String text = wire(out);
        assertTrue(text.contains("Transfer-Encoding: chunked"));
        assertTrue(text.endsWith("5\r\nhello\r\n0\r\n\r\n"),
            "a streaming response must end with the terminal chunk: " + text);
    }

    @Test
    void http1BufferedGzipRoundTrips() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("POST",
            Map.of("accept-encoding", List.of("gzip")), out);
        ctx.setHeader("Content-Type", "text/plain");
        String original = "hello gzip ".repeat(64);
        ctx.body(original.getBytes(StandardCharsets.UTF_8));

        String text = wire(out);
        assertTrue(text.contains("Content-Encoding: gzip"));
        assertTrue(text.contains("Content-Length: "));
        assertFalse(text.contains("Transfer-Encoding"));

        byte[] gz = bodyAfterHeaders(out);
        try (var gin = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            assertEquals(original, new String(gin.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void http1StreamingGzipUsesChunked() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("POST",
            Map.of("accept-encoding", List.of("gzip")), out);
        ctx.setHeader("Content-Type", "text/plain");
        byte[] body = "stream ".repeat(64).getBytes(StandardCharsets.UTF_8);

        ctx.body(new ByteArrayInputStream(body), body.length);

        String text = wire(out);
        assertTrue(text.contains("Content-Encoding: gzip"));
        assertTrue(text.contains("Transfer-Encoding: chunked"));
        assertFalse(text.contains("Content-Length"));
    }

    @Test
    void http1StreamingBodyLongerThanContentLengthFails() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        assertThrows(java.io.IOException.class,
            () -> ctx.body(new ByteArrayInputStream("hello!".getBytes(StandardCharsets.UTF_8)), 5));

        String text = wire(out);
        assertTrue(text.contains("Content-Length: 5"));
        assertTrue(text.endsWith("hello"),
            "the bytes past the declared length must not reach the wire: " + text);
    }

    @Test
    void http1StreamingBodyShorterThanContentLengthFails() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        assertThrows(java.io.IOException.class,
            () -> ctx.body(new ByteArrayInputStream("hi".getBytes(StandardCharsets.UTF_8)), 5));

        assertTrue(ctx.isCommitted(),
            "the head was already written; the failure is a transport failure, not a retry");
    }

    @Test
    void http1HeadSuppressesBodyButKeepsLength() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("HEAD", Map.of(), out);

        ctx.sendText(200, "hello");

        String text = wire(out);
        assertTrue(text.contains("Content-Length: 5"));
        assertFalse(text.contains("hello"));
    }

    @Test
    void sseForcesConnectionCloseAndChunkedFraming() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = exchange("GET", Map.of(), out);

        try (var emitter = ctx.sse()) {
            emitter.send("data: hi\n\n");
        }

        String text = wire(out);
        assertTrue(text.startsWith("HTTP/1.1 200"));
        assertTrue(text.contains("Connection: close"),
            "SSE terminates the HTTP/1.1 exchange: " + text);
        assertTrue(text.contains("Transfer-Encoding: chunked"));
        assertFalse(text.contains("Content-Length"));
    }

    // -- files / sendfile --

    @Test
    void fileUsesSendfileForLargeFile() throws Exception {
        Path file = Files.createTempFile("sendfile", ".bin");
        try {
            int size = 64 * 1024;
            Files.write(file, new byte[size]);
            var out = new ByteArrayOutputStream();
            var transport = new HttpTransportImpl("GET", "/", null, Map.of(),
                new ByteArrayInputStream(new byte[0]), -1, false, out, true,
                false, null, "");
            long[] transferred = new long[2];
            transport.setFileSender((channel, offset, length) -> {
                transferred[0] = offset;
                transferred[1] = length;
            });
            var ctx = new HttpExchangeSupport(transport, new JsonCodecDefault(),
                new CoercerDefault(), HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
                COMPRESSION, null);

            try (var channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.READ)) {
                ctx.file(channel, 0, size);
            }

            assertEquals(0L, transferred[0]);
            assertEquals((long) size, transferred[1]);
            assertTrue(wire(out).contains("Content-Length: " + size));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void headOutputFileSkipsSendfileTransfer() throws Exception {
        Path file = Files.createTempFile("head-sendfile", ".bin");
        try {
            var out = new ByteArrayOutputStream();
            var transport = new HttpTransportImpl("HEAD", "/", null, Map.of(),
                new ByteArrayInputStream(new byte[0]), -1, false, out, true,
                false, null, "");
            boolean[] transferred = {false};
            transport.setFileSender((ch, offset, length) -> transferred[0] = true);
            var ctx = new HttpExchangeSupport(transport, new JsonCodecDefault(),
                new CoercerDefault(), HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
                COMPRESSION, null);

            try (var channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.READ)) {
                ctx.file(channel, 0, 64 * 1024);
            }

            assertFalse(transferred[0],
                "HEAD must never transfer file bytes on the sendfile path");
            assertEquals("65536", ctx.responseHeader("Content-Length"),
                "HEAD must report the same Content-Length as GET");
            assertTrue(ctx.isCommitted());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void unknownFileLengthIsResolvedBeforeSendfileCommitsHead() throws Exception {
        Path file = Files.createTempFile("sendfile-unknown", ".bin");
        try {
            int size = 64 * 1024;
            Files.write(file, new byte[size]);
            var out = new ByteArrayOutputStream();
            var transport = new HttpTransportImpl("GET", "/", null, Map.of(),
                new ByteArrayInputStream(new byte[0]), -1, false, out, true,
                false, null, "");
            long[] transferred = new long[1];
            transport.setFileSender((channel, offset, length) -> transferred[0] = length);
            var ctx = new HttpExchangeSupport(transport, new JsonCodecDefault(),
                new CoercerDefault(), HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
                COMPRESSION, null);

            try (var channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.READ)) {
                ctx.file(channel, 0, -1);
            }

            assertEquals((long) size, transferred[0],
                "the sendfile path must receive the resolved range length");
            String text = wire(out);
            assertTrue(text.contains("Content-Length: " + size),
                "a resolved file length must be declared before commit: " + text);
            assertFalse(text.contains("Transfer-Encoding"),
                "the sendfile bytes are not chunk-framed, so the head cannot say chunked");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void fileStreamsSmallFileThroughBuffer() throws Exception {
        Path file = Files.createTempFile("small", ".bin");
        try {
            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
            Files.write(file, content);
            var out = new ByteArrayOutputStream();
            var transport = new HttpTransportImpl("GET", "/", null, Map.of(),
                new ByteArrayInputStream(new byte[0]), -1, false, out, true,
                false, null, "");
            transport.setFileSender((c, o, l) -> {
                throw new AssertionError("small file must not use sendfile");
            });
            var ctx = new HttpExchangeSupport(transport, new JsonCodecDefault(),
                new CoercerDefault(), HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
                COMPRESSION, null);

            try (var channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.READ)) {
                ctx.file(channel, 0, content.length);
            }

            String text = wire(out);
            assertTrue(text.contains("Content-Length: 5"));
            assertTrue(text.endsWith("hello"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void getOutputFileStillUsesSendfileTransfer() throws Exception {
        Path file = Files.createTempFile("get-sendfile", ".bin");
        try {
            var out = new ByteArrayOutputStream();
            var transport = new HttpTransportImpl("GET", "/", null, Map.of(),
                new ByteArrayInputStream(new byte[0]), -1, false, out, true,
                false, null, "");
            boolean[] transferred = {false};
            transport.setFileSender((ch, offset, length) -> transferred[0] = true);
            var ctx = new HttpExchangeSupport(transport, new JsonCodecDefault(),
                new CoercerDefault(), HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
                COMPRESSION, null);

            try (var channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.READ)) {
                ctx.file(channel, 0, 64 * 1024);
            }

            assertTrue(transferred[0], "GET must keep using the sendfile transfer path");
            assertEquals("65536", ctx.responseHeader("Content-Length"));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
