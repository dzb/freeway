package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.body.BodyTooLargeException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestBodyTest {

    @Test
    void fixedLengthReadsExactBytes() throws Exception {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 3, false, () -> 1024);
        assertArrayEquals("abc".getBytes(), body.readAll());
    }

    @Test
    void chunkedReadsReassembledBody() throws Exception {
        var raw = new ByteArrayInputStream("3\r\nabc\r\n0\r\n\r\n".getBytes());
        var body = new RequestBody(raw, -1, true, () -> 1024);
        assertArrayEquals("abc".getBytes(), body.readAll());
    }

    @Test
    void overLimitThrowsAndMarksExceeded() {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 6, false, () -> 3);
        assertThrows(BodyTooLargeException.class, body::readAll);
        assertTrue(body.limitExceeded());
    }

    @Test
    void drainConsumesWithinLimit() {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 6, false, () -> 1024);
        assertTrue(body.drain());
    }

    @Test
    void streamReadsFixedLengthBody() throws Exception {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 3, false, () -> 1024);
        assertArrayEquals("abc".getBytes(), body.stream().readAllBytes());
    }

    @Test
    void streamReadsChunkedBody() throws Exception {
        var raw = new ByteArrayInputStream("3\r\nabc\r\n0\r\n\r\n".getBytes());
        var body = new RequestBody(raw, -1, true, () -> 1024);
        assertArrayEquals("abc".getBytes(), body.stream().readAllBytes());
    }

    @Test
    void streamThrowsWhenBodyExceedsLimit() {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 6, false, () -> 3);
        assertThrows(BodyTooLargeException.class,
            () -> body.stream().readAllBytes());
        assertTrue(body.limitExceeded());
    }

    @Test
    void streamEndsCleanlyAtExactLimit() throws Exception {
        var raw = new ByteArrayInputStream("abc".getBytes());
        var body = new RequestBody(raw, 3, false, () -> 3);
        assertArrayEquals("abc".getBytes(), body.stream().readAllBytes());
        assertFalse(body.limitExceeded());
    }

    @Test
    void partialStreamReadThenDrainAllowsReuse() throws Exception {
        var raw = new ByteArrayInputStream("abcdef".getBytes());
        var body = new RequestBody(raw, 6, false, () -> 1024);
        assertEquals(97, body.stream().read());
        assertTrue(body.drain());
    }
}
