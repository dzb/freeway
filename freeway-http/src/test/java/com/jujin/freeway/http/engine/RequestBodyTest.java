package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request body framing and drain — everything {@code RequestBody} still owns
 * after size limiting moved to {@code HttpContextSupport}. The limit itself
 * is asserted through the support object in
 * {@code HttpContextSupportBodyLimitTest}.
 */
class RequestBodyTest {

    @Test
    void fixedLengthReadsExactBytes() throws Exception {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 3, false);
        assertArrayEquals("abc".getBytes(), body.stream().readAllBytes());
    }

    @Test
    void chunkedReadsReassembledBody() throws Exception {
        var raw = new ByteArrayInputStream("3\r\nabc\r\n0\r\n\r\n".getBytes());
        var body = new RequestBody(raw, -1, true);
        assertArrayEquals("abc".getBytes(), body.stream().readAllBytes());
    }

    @Test
    void streamReadsFixedLengthBody() throws Exception {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 3, false);
        var in = body.stream();
        assertArrayEquals("abc".getBytes(), in.readAllBytes());
        assertSame(in, body.stream(),
            "the framed stream is handed out once, not re-created per call");
        assertArrayEquals(new byte[0], body.stream().readAllBytes(),
            "a second read of an EOF-ed stream yields nothing");
    }

    @Test
    void streamReadsChunkedBody() throws Exception {
        var raw = new ByteArrayInputStream("3\r\nabc\r\n0\r\n\r\n".getBytes());
        var body = new RequestBody(raw, -1, true);
        assertArrayEquals("abc".getBytes(), body.stream().readAllBytes());
    }

    @Test
    void drainConsumesWithinLimit() {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 6, false);
        assertTrue(body.drain(1024));
    }

    @Test
    void drainOfAlreadyConsumedBodyAllowsReuse() throws Exception {
        var raw = new ByteArrayInputStream("abc".getBytes());
        var body = new RequestBody(raw, 3, false);
        assertArrayEquals("abc".getBytes(), body.stream().readAllBytes());
        assertTrue(body.drain(3), "an EOF-ed body must drain as consumed");
    }

    @Test
    void partialStreamReadThenDrainAllowsReuse() throws Exception {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 6, false);
        assertEquals(97, body.stream().read());
        assertTrue(body.drain(1024));
    }

    @Test
    void drainCountsBytesTheHandlerAlreadyRead() {
        // The support object's limiter consumed 4 of the 6 bytes; the drain's
        // allowance is what is left of the ceiling, not the whole ceiling —
        // otherwise a handler that read to the limit could drain past it.
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 6, false);
        try {
            body.stream().readNBytes(4);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(body.drain(6), "2 bytes left under a ceiling of 6");
    }

    @Test
    void drainOverLimitByContentLengthRefusesReuse() {
        var raw = new ByteArrayInputStream("hello world".getBytes());
        var body = new RequestBody(raw, 11, false);
        assertFalse(body.drain(5),
            "an over-limit body must not allow connection reuse");
    }

    @Test
    void drainOverLimitChunkedRefusesReuse() {
        byte[] raw = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            .getBytes();
        var body = new RequestBody(new ByteArrayInputStream(raw), -1, true);
        assertFalse(body.drain(7),
            "an over-limit chunked body must not allow connection reuse");
    }

    @Test
    void drainWithNoBodyAllowsReuse() {
        var body = new RequestBody(new ByteArrayInputStream(new byte[0]), 0, false);
        assertTrue(body.drain(1024));
    }
}
