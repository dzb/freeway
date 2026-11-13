package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

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
 * The engine-side helpers {@code Http1xSession} drives after a response:
 * body drain (keep-alive reuse), the {@code Connection} header sync, and the
 * request snapshots handed to the support object.
 */
class HttpTransportImplTest {

    private record Fixture(HttpTransportImpl transport, HttpExchangeSupport ctx) {}

    private static Fixture exchange(byte[] raw, long contentLength,
            boolean chunked, Map<String, List<String>> headers, long limit) {
        var transport = new HttpTransportImpl("POST", "/", null, headers,
            new ByteArrayInputStream(raw), contentLength, chunked,
            new ByteArrayOutputStream(), true, false, null, "");
        var ctx = new HttpExchangeSupport(transport, new JsonCodecDefault(),
            new CoercerDefault(), limit,
            HttpServerConfig.CompressionConfig.DEFAULT, null);
        return new Fixture(transport, ctx);
    }

    @Test
    void drainOfAnOverLimitBodyRefusesConnectionReuse() throws Exception {
        byte[] raw = "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            .getBytes(StandardCharsets.ISO_8859_1);
        Fixture f = exchange(raw, -1, true, Map.of(), 7);

        assertFalse(f.transport().drainRequestBody(f.ctx().bodyLimit()),
            "an over-limit body must not allow keep-alive reuse");
    }

    @Test
    void drainOfAFullyReadBodyAllowsReuse() throws Exception {
        Fixture f = exchange("hello".getBytes(StandardCharsets.ISO_8859_1),
            5, false, Map.of(), 10);

        assertEquals(5, f.ctx().body().length);
        assertTrue(f.transport().drainRequestBody(f.ctx().bodyLimit()),
            "a body the handler already consumed must drain as consumed");
    }

    @Test
    void drainOfAnUnreadBodyWithinTheLimitAllowsReuse() {
        Fixture f = exchange("hello".getBytes(StandardCharsets.ISO_8859_1),
            5, false, Map.of(), 10);

        assertTrue(f.transport().drainRequestBody(f.ctx().bodyLimit()));
    }

    @Test
    void committedFlagFlipsOnlyWhenTheResponseIsWritten() throws Exception {
        Fixture f = exchange("hello".getBytes(StandardCharsets.ISO_8859_1),
            5, false, Map.of(), 10);
        assertFalse(f.transport().isCommitted(),
            "a fresh transport has not written a response");

        f.ctx().sendText(200, "ok");

        assertTrue(f.transport().isCommitted(),
            "a written response has started, and the support object reads that fact");
        assertTrue(f.ctx().isCommitted());
    }

    @Test
    void syncKeepAliveFromResponseClosesTheConnection() {
        var transport = new HttpTransportImpl("GET", "/", null, Map.of(),
            new ByteArrayInputStream(new byte[0]), -1, false,
            new ByteArrayOutputStream(), true, false, null, "");
        assertTrue(transport.isKeepAlive());

        transport.syncKeepAliveFromResponse("close");
        assertFalse(transport.isKeepAlive());

        transport.syncKeepAliveFromResponse("keep-alive");
        assertTrue(transport.isKeepAlive());

        transport.syncKeepAliveFromResponse(null);
        assertTrue(transport.isKeepAlive(), "a null header changes nothing");
    }

    @Test
    void requestSnapshotsAreImmutableAndCanonical() {
        var transport = new HttpTransportImpl("GET", "/x", "a=1&a=2",
            Map.of("Traceparent", List.of("00-abc"), "X-Custom", List.of("v")),
            new ByteArrayInputStream(new byte[0]), -1, false,
            new ByteArrayOutputStream(), true, false, null, "127.0.0.1");

        assertTrue(transport.headers().containsKey("traceparent"),
            "header names are lower-cased once, at this boundary");
        assertEquals("127.0.0.1", transport.remoteAddress());
        assertEquals(List.of("1", "2"), transport.queryParams().get("a"));
        assertThrows(UnsupportedOperationException.class,
            () -> transport.headers().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class,
            () -> transport.queryParams().get("a").add("3"));
    }
}
