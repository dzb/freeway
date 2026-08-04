package com.jujin.freeway.http.engine;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpContextDefaultTest {

    private static final com.jujin.freeway.commons.json.JsonCodec CODEC =
            new com.jujin.freeway.commons.json.JsonCodecDefault();
    private static final com.jujin.freeway.commons.coercion.Coercer COERCER =
            new com.jujin.freeway.commons.coercion.CoercerDefault();

    /** Simulates an H2 stream's header map without needing a full Http2Connection. */
    private static Http2ResponseBridge mockBridge() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        return () -> headers;
    }

    @Test
    void h2BodyOnlyNoWireFormat() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextDefault(CODEC, COERCER);
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
        var ctx = new HttpContextDefault(CODEC, COERCER);
        var bridge = mockBridge();
        ctx.h2Bridge = bridge;

        ctx.headerSet("X-Custom", "abc");

        assertEquals(List.of("abc"), bridge.headers().get("X-Custom"),
                "headerSet must route to H2 bridge headers");
    }

    @Test
    void headerSetRejectsCRLFInName() {
        var ctx = new HttpContextDefault(CODEC, COERCER);
        assertThrows(IllegalArgumentException.class, () ->
            ctx.headerSet("X-Test\r\nInjected", "yes"),
                "Header name with CRLF must be rejected");
        assertThrows(IllegalArgumentException.class, () ->
            ctx.headerSet("X:Test", "yes"),
                "Header name with colon must be rejected");
    }

    @Test
    void h2SseIncludesContentType() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        var bridge = mockBridge();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.h2Bridge = bridge;
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        try (var emitter = ctx.sse()) {
            emitter.send("ok");
        }

        assertTrue(bridge.headers().containsKey("content-type"),
                "H2 SSE should set Content-Type: " + bridge.headers());
        assertEquals(List.of("text/event-stream; charset=utf-8"),
                bridge.headers().get("content-type"));
        assertTrue(bridge.headers().containsKey("cache-control"),
                "H2 SSE should set Cache-Control: " + bridge.headers());
    }

    @Test
    void h2SseSkipsHttp1Framing() throws Exception {
        var out = new ByteArrayOutputStream();
        var bridge = mockBridge();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.h2Bridge = bridge;
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        try (var emitter = ctx.sse()) {
            emitter.send("ok");
        }

        String wire = out.toString();
        assertFalse(wire.contains("HTTP/1.1"),
                "H2 SSE must not write HTTP/1.1 status line: " + wire);
        assertFalse(wire.contains("chunked"),
                "H2 SSE must not write Transfer-encoding: " + wire);
        assertTrue(wire.contains("data: ok"),
                "H2 SSE should write event data directly: " + wire);
    }

    @Test
    void h2OutputRespectsNoBodyStatus() throws Exception {
        var out = new ByteArrayOutputStream();
        var bridge = mockBridge();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.h2Bridge = bridge;
        ctx.reset("POST", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.status(204);
        ctx.send(204, "should-not-appear");

        String wire = out.toString();
        assertEquals("", wire, "H2 204 should write no body, got: " + wire);
    }

    @Test
    void queryParamsAreDeeplyUnmodifiable() {
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.reset("GET", "/path", "a=1&a=2", Map.of(), null, -1, false,
                new java.io.ByteArrayOutputStream(), null, false, false);

        var params = ctx.queryParams();
        assertThrows(UnsupportedOperationException.class, () -> params.put("b", List.of()),
                "Top-level map must be unmodifiable");
        assertThrows(UnsupportedOperationException.class, () -> params.get("a").add("3"),
                "Inner value list must be unmodifiable");
    }

    @Test
    void h2DefaultsToHttp1WhenNoBridge() throws Exception {
        var out = new ByteArrayOutputStream();
        var ctx = new HttpContextDefault(CODEC, COERCER);
        ctx.reset("GET", "/", null, Map.of(), null, -1, false, out, null, false, false);

        ctx.send(200, "ok");

        String wire = out.toString();
        assertTrue(wire.contains("HTTP/1.1"),
                "Without H2 bridge, should write HTTP/1.1 wire format");
    }
}
