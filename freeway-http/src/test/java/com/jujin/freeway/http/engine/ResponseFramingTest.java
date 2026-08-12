package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.HttpServerConfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseFramingTest {

    private static final HttpServerConfig.CompressionConfig ON =
        new HttpServerConfig.CompressionConfig(true, 256);
    private static final HttpServerConfig.CompressionConfig OFF =
        new HttpServerConfig.CompressionConfig(false, 256);

    @Test
    void gzipRequiresEveryGate() {
        assertTrue(ResponseFraming.shouldGzip(
            ON, 200, true, 1024, true, true));
        // Each gate must be able to switch gzip off.
        assertFalse(ResponseFraming.shouldGzip(
            OFF, 200, true, 1024, true, true), "compression disabled");
        assertFalse(ResponseFraming.shouldGzip(
            ON, 206, true, 1024, true, true), "206 partial content");
        assertFalse(ResponseFraming.shouldGzip(
            ON, 200, false, 1024, true, true), "no body allowed");
        assertFalse(ResponseFraming.shouldGzip(
            ON, 200, true, 64, true, true), "below min size");
        assertFalse(ResponseFraming.shouldGzip(
            ON, 200, true, 1024, false, true), "client did not negotiate");
        assertFalse(ResponseFraming.shouldGzip(
            ON, 200, true, 1024, true, false), "not compressible");
    }

    @Test
    void streamGzipSkipsMinSizeGate() {
        assertTrue(ResponseFraming.shouldGzipStream(
            ON, 200, true, true, true));
        assertFalse(ResponseFraming.shouldGzipStream(
            OFF, 200, true, true, true));
        assertFalse(ResponseFraming.shouldGzipStream(
            ON, 206, true, true, true));
        assertFalse(ResponseFraming.shouldGzipStream(
            ON, 200, false, true, true));
        assertFalse(ResponseFraming.shouldGzipStream(
            ON, 200, true, false, true));
    }

    @Test
    void fileGzipRequiresBodyAllowed() {
        assertTrue(ResponseFraming.shouldGzipFile(
            ON, 200, true, true, true));
        assertFalse(ResponseFraming.shouldGzipFile(
            OFF, 200, true, true, true));
        assertFalse(ResponseFraming.shouldGzipFile(
            ON, 206, true, true, true));
        assertFalse(ResponseFraming.shouldGzipFile(
            ON, 204, false, true, true));
    }

    @Test
    void bodySuppressionTable() {
        assertFalse(ResponseFraming.suppressBodyBytes(true, "GET"));
        assertFalse(ResponseFraming.suppressBodyBytes(true, "POST"));
        assertTrue(ResponseFraming.suppressBodyBytes(true, "HEAD"),
            "HEAD must suppress body bytes");
        assertTrue(ResponseFraming.suppressBodyBytes(false, "GET"),
            "204/205/304 must suppress body bytes");
    }
}
