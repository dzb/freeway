package com.jujin.freeway.http.engine;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreewayHttpContextTest {

    private static final com.jujin.freeway.commons.json.JsonCodec CODEC =
            new com.jujin.freeway.commons.json.JsonCodecDefault();
    private static final com.jujin.freeway.commons.coercion.Coercer COERCER =
            new com.jujin.freeway.commons.coercion.CoercerDefault();

    /** Simulates an H2 stream's header map without needing a full Http2Connection. */
    private static H2ResponseBridge mockBridge() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        return () -> headers;
    }

    @Test
    void h2BodyOnlyNoWireFormat() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new FreewayHttpContext(CODEC, COERCER);
        ctx.h2Bridge = mockBridge();
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.status(200);
        ctx.send(200, "hello");

        String wire = out.toString();
        assertFalse(wire.contains("HTTP/1.1"),
                "H2 mode must not write HTTP/1.1 status line: " + wire);
        assertFalse(wire.contains("Content-Length"),
                "H2 mode must not write Content-Length: " + wire);
        assertEquals("hello", wire, "H2 mode writes body only");
    }

    @Test
    void h2HeaderSetRoutesToBridge() {
        var ctx = new FreewayHttpContext(CODEC, COERCER);
        var bridge = mockBridge();
        ctx.h2Bridge = bridge;

        ctx.headerSet("X-Custom", "abc");

        assertEquals(List.of("abc"), bridge.headers().get("X-Custom"),
                "headerSet must route to H2 bridge headers");
    }

    @Test
    void headerSetRejectsCRLFInName() {
        var ctx = new FreewayHttpContext(CODEC, COERCER);
        assertThrows(IllegalArgumentException.class, () ->
            ctx.headerSet("X-Test\r\nInjected", "yes"),
                "Header name with CRLF must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
            ctx.headerSet("X:Test", "yes"),
                "Header name with colon must be rejected");
    }

    @Test
    void h2DefaultsToHttp1WhenNoBridge() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new FreewayHttpContext(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.send(200, "ok");

        String wire = out.toString();
        assertTrue(wire.contains("HTTP/1.1"),
                "Without H2 bridge, should write HTTP/1.1 wire format");
    }
}
