package com.jujin.freeway.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accept-Encoding negotiation contract shared by the built-in engine and the
 * transport adapters: an absent header is "no preference" and does not
 * compress, {@code gzip} enables it, {@code gzip;q=0} refuses it.
 */
class CompressionTest {

    @Test
    void absentHeaderDoesNotCompress() {
        assertFalse(Compression.acceptsGzip((String) null));
        assertFalse(Compression.acceptsGzip((List<String>) null));
        assertFalse(Compression.acceptsGzip(List.of()));
        assertFalse(Compression.acceptsGzip(""));
    }

    @Test
    void plainGzipTokenEnablesCompression() {
        assertTrue(Compression.acceptsGzip("gzip"));
        assertTrue(Compression.acceptsGzip("GZIP"));
        assertTrue(Compression.acceptsGzip("deflate, gzip"));
        assertTrue(Compression.acceptsGzip(" gzip "));
    }

    @Test
    void qZeroRefusesCompression() {
        assertFalse(Compression.acceptsGzip("gzip;q=0"));
        assertFalse(Compression.acceptsGzip("gzip;q=0.0"));
        assertFalse(Compression.acceptsGzip("br, gzip; Q=0"));
        assertFalse(Compression.acceptsGzip("gzip;q=0, deflate"));
    }

    @Test
    void otherQualityValuesEnableCompression() {
        assertTrue(Compression.acceptsGzip("gzip;q=0.5"));
        assertTrue(Compression.acceptsGzip("gzip;q=1"));
        // A malformed q is not "q=0": the client named gzip, so compress.
        assertTrue(Compression.acceptsGzip("gzip;q=abc"));
        assertTrue(Compression.acceptsGzip("gzip;level=9"));
    }

    @Test
    void otherEncodingsDoNotEnableGzip() {
        assertFalse(Compression.acceptsGzip("deflate"));
        assertFalse(Compression.acceptsGzip("br, deflate"));
        assertFalse(Compression.acceptsGzip("*"));
    }

    @Test
    void repeatedHeaderLinesAreEachConsulted() {
        assertTrue(Compression.acceptsGzip(List.of("br", "gzip")));
        assertTrue(Compression.acceptsGzip(List.of("gzip;q=0", "gzip")));
        assertFalse(Compression.acceptsGzip(List.of("br", "gzip;q=0")));
    }

    @Test
    void gzipRoundTrips() throws IOException {
        byte[] body = "freeway compression round-trip".repeat(20).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = Compression.gzip(body);

        assertTrue(compressed.length < body.length, "repetitive input must actually shrink");
        try (var in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            assertArrayEquals(body, in.readAllBytes());
        }
        // Gzip of an empty body is a valid (header + trailer) stream, not zero bytes.
        try (var in = new GZIPInputStream(new ByteArrayInputStream(Compression.gzip(new byte[0])))) {
            assertArrayEquals(new byte[0], in.readAllBytes());
        }
    }
}
