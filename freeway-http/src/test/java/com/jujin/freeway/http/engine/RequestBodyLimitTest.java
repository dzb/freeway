package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.body.BodyTooLargeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: maxBodySize must bind every read path of {@link RequestBody},
 * not just {@code readAll()}. A handler that consumes the body through the
 * wrapped stream (chunked, fixed-length, or HTTP/2-style unknown length)
 * must hit {@link BodyTooLargeException} the moment the cumulative read
 * crosses the limit.
 */
class RequestBodyLimitTest {

    private static InputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void streamingReadWithinLimitReadsAllBytes() throws Exception {
        var body = new RequestBody(bytes("hello"), 5, false, () -> 10L);
        try (var in = body.stream()) {
            assertEquals("hello",
                new String(in.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        assertFalse(body.limitExceeded(),
            "a body within the limit must not flag limitExceeded");
    }

    @Test
    void streamingReadExactlyAtLimitSucceeds() throws Exception {
        var body = new RequestBody(bytes("hello"), 5, false, () -> 5L);
        try (var in = body.stream()) {
            assertEquals(5, in.readAllBytes().length);
        }
        assertFalse(body.limitExceeded(),
            "a body exactly at the limit must be accepted");
    }

    @Test
    void streamingReadExceedingLimitThrowsBodyTooLarge() throws Exception {
        var body = new RequestBody(bytes("hello world"), 11, false, () -> 5L);
        try (var in = body.stream()) {
            assertThrows(BodyTooLargeException.class, in::readAllBytes);
        }
        assertTrue(body.limitExceeded(),
            "an over-limit streaming read must flag limitExceeded");
    }

    @Test
    void chunkedStreamingReadEnforcesLimit() throws Exception {
        byte[] raw = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);
        var body = new RequestBody(new ByteArrayInputStream(raw), -1, true, () -> 7L);
        try (var in = body.stream()) {
            assertThrows(BodyTooLargeException.class, in::readAllBytes);
        }
        assertTrue(body.limitExceeded());
    }

    @Test
    void rawUnknownLengthStreamingReadEnforcesLimit() throws Exception {
        // HTTP/2-style body: unknown length, not chunked — the raw input
        // stream must still be bounded by the counting filter.
        var body = new RequestBody(bytes("abcdef"), -1, false, () -> 3L);
        try (var in = body.stream()) {
            assertThrows(BodyTooLargeException.class, in::readAllBytes);
        }
        assertTrue(body.limitExceeded());
    }

    @Test
    void readAllOverLimitStillThrows() throws Exception {
        var body = new RequestBody(bytes("abcdef"), 6, false, () -> 3L);
        assertThrows(BodyTooLargeException.class, body::readAll);
        assertTrue(body.limitExceeded());
    }

    @Test
    void readAllWithinLimitReturnsCachedBody() throws Exception {
        var body = new RequestBody(bytes("abc"), 3, false, () -> 3L);
        assertEquals("abc", new String(body.readAll(), StandardCharsets.ISO_8859_1));
        assertEquals("abc", new String(body.readAll(), StandardCharsets.ISO_8859_1),
            "readAll must cache its result");
    }

    @Test
    void drainOverLimitFixedLengthRefusesReuse() {
        var body = new RequestBody(bytes("hello world"), 11, false, () -> 5L);
        assertFalse(body.drain(),
            "an over-limit body must not allow connection reuse");
    }

    @Test
    void drainOverLimitChunkedRefusesReuse() {
        byte[] raw = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);
        var body = new RequestBody(new ByteArrayInputStream(raw), -1, true, () -> 7L);
        assertFalse(body.drain(),
            "an over-limit chunked body must not allow connection reuse");
        assertTrue(body.limitExceeded());
    }

    @Test
    void drainWithinLimitConsumesAndReturnsTrue() throws Exception {
        var body = new RequestBody(bytes("hello"), 5, false, () -> 10L);
        assertTrue(body.drain());
        assertFalse(body.limitExceeded());
    }

    @Test
    void skipIsCountedAgainstTheLimit() throws Exception {
        var body = new RequestBody(bytes("hello"), 5, false, () -> 3L);
        try (var in = body.stream()) {
            assertThrows(BodyTooLargeException.class, () -> in.skip(5));
        }
        assertTrue(body.limitExceeded());
    }
}
