package com.jujin.freeway.http;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.engine.HttpTransportImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every request-body read path must hit the one shared limiter
 * ({@code LimitedInputStream}) the support object wraps the transport stream
 * with: buffered {@code body()}, streaming {@code bodyStream()}, skip, and
 * the transport's chunked/fixed framing underneath. This is the shape the
 * old {@code RequestBody} limit tests pinned, relocated to where the limit
 * now lives.
 */
class HttpExchangeSupportBodyLimitTest {

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static TestExchange exchange(String body, long limit) {
        TestExchange ctx = new TestExchange("POST", "/")
            .requestBody(bytes(body));
        ctx.setMaxBodySize(limit);
        return ctx;
    }

    @Test
    void streamingReadWithinLimitReadsAllBytes() throws Exception {
        var ctx = exchange("hello", 10);
        try (var in = ctx.bodyStream()) {
            assertEquals("hello",
                new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    void streamingReadExactlyAtLimitSucceeds() throws Exception {
        var ctx = exchange("hello", 5);
        try (var in = ctx.bodyStream()) {
            assertEquals(5, in.readAllBytes().length,
                "a body exactly at the limit must be accepted");
        }
    }

    @Test
    void streamingReadExceedingLimitThrowsBodyTooLarge() {
        var ctx = exchange("hello world", 5);
        assertThrows(BodyTooLargeException.class, () -> {
            try (var in = ctx.bodyStream()) {
                in.readAllBytes();
            }
        });
    }

    @Test
    void readAllOverLimitStillThrows() {
        var ctx = exchange("abcdef", 3);
        assertThrows(BodyTooLargeException.class, ctx::body);
    }

    @Test
    void readAllWithinLimitIsCached() throws Exception {
        var ctx = exchange("abc", 3);
        assertEquals("abc", new String(ctx.body(), StandardCharsets.ISO_8859_1));
        assertEquals("abc", new String(ctx.body(), StandardCharsets.ISO_8859_1),
            "body() must cache its result");
    }

    @Test
    void skipIsCountedAgainstTheLimit() {
        var ctx = exchange("hello", 3);
        assertThrows(BodyTooLargeException.class, () -> ctx.bodyStream().skip(5));
    }

    @Test
    void limitAppliesToThatExchangeOnly() throws Exception {
        TestExchange strict = exchange("hello", 2);
        assertThrows(BodyTooLargeException.class, strict::body);

        TestExchange loose = exchange("hello", 100);
        assertEquals(5, loose.body().length,
            "a narrowed limit on one exchange must not govern the next");
    }

    @Test
    void chunkedFramingRunsUnderTheSharedLimiter() {
        // The built-in transport frames the chunked body; the support object
        // still bounds the framed bytes it hands the handler.
        byte[] raw = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);
        var transport = new HttpTransportImpl("POST", "/", null,
            java.util.Map.of(), new ByteArrayInputStream(raw), -1, true,
            new java.io.ByteArrayOutputStream(), true, false, null, "");
        var ctx = new HttpExchangeSupport(transport,
            new com.jujin.freeway.commons.json.JsonCodecDefault(),
            new com.jujin.freeway.commons.coercion.CoercerDefault(),
            7, HttpServerConfig.CompressionConfig.DEFAULT, null);

        assertThrows(BodyTooLargeException.class, ctx::body,
            "the shared limiter must bound the chunked body too");
    }
}
