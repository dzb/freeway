package com.jujin.freeway.http.engine;

import static org.junit.jupiter.api.Assertions.*;

import com.jujin.freeway.http.engine.http11.HttpParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HttpParserTest {

    @Test
    void parseWebSocketUpgradeRequest() throws IOException {
        String raw = "GET /api/ws/lobby HTTP/1.1\r\n"
            + "Host: 127.0.0.1:8080\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Origin: http://127.0.0.1:8080\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertEquals("GET", req.method());
        assertEquals("/api/ws/lobby", req.path());
        assertTrue(req.isUpgradeRequest());

        // header names are normalized to lowercase per RFC 7230
        assertEquals("websocket", headerValue(req, "upgrade"));
        assertEquals("Upgrade", headerValue(req, "connection"));
        assertEquals("13", headerValue(req, "sec-websocket-version"));
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", headerValue(req, "sec-websocket-key"));
    }

    // ── Header key normalization ──────────────────────────────────

    @Test
    void headerKeysNormalizedToLowercase() throws IOException {
        String raw = "GET /test HTTP/1.1\r\n"
            + "X-Request-Id: abc-123\r\n"
            + "Content-Type: application/json\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        // keys are all lowercase
        assertEquals("abc-123", headerValue(req, "x-request-id"));
        assertEquals("application/json", headerValue(req, "content-type"));
        // exact-case lookup must work
        assertNotNull(req.headers().get("x-request-id"));
        assertEquals("abc-123", req.headers().get("x-request-id").getFirst());
    }

    // ── Header value OWS tolerance ─────────────────────────────────

    @Test
    void contentLengthWithTrailingSpace() throws IOException {
        String raw = "POST /test HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Length: 4 \r\n"
            + "\r\n"
            + "abcd";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertEquals(4, req.contentLength());
    }

    @Test
    void connectionKeepAliveWithTrailingSpace() throws IOException {
        String raw = "GET /test HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Connection: keep-alive \r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertTrue(req.keepAlive());
    }

    @Test
    void upgradeWebsocketWithTrailingSpace() throws IOException {
        String raw = "GET /ws HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Upgrade: websocket \r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertTrue(req.isUpgradeRequest());
    }

    // ── Connection header token list ──────────────────────────────

    @Test
    void connectionTokenListKeepAliveAndUpgrade() throws IOException {
        String raw = "GET /ws HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: keep-alive, Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertTrue(req.keepAlive());
        assertTrue(req.isUpgradeRequest());
    }

    @Test
    void connectionTokenListClose() throws IOException {
        String raw = "GET /test HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Connection: keep-alive, close\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertFalse(req.keepAlive()); // "close" takes precedence
    }

    @Test
    void headerValueWithLeadingTabAfterColon() throws IOException {
        String raw = "GET /test HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Length:\t4\r\n"
            + "\r\n"
            + "abcd";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertEquals(4, req.contentLength());
    }

    @Test
    void parseSimpleGet() throws IOException {
        String raw = "GET /ping HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertEquals("GET", req.method());
        assertEquals("/ping", req.path());
        assertFalse(req.isUpgradeRequest());
    }

    @Test
    void parsePostWithBody() throws IOException {
        String raw = "POST /echo HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Length: 4\r\n"
            + "\r\n"
            + "abcd";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertEquals("POST", req.method());
        assertEquals(4, req.contentLength());
        assertFalse(req.isChunked());
    }

    @Test
    void parseHttp10() throws IOException {
        String raw = "GET /old HTTP/1.0\r\n"
            + "Host: localhost\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertTrue(req.isHttp10());
        assertFalse(req.keepAlive()); // HTTP/1.0 without keep-alive
    }

    @Test
    void parseHttp10KeepAlive() throws IOException {
        String raw = "GET /old HTTP/1.0\r\n"
            + "Host: localhost\r\n"
            + "Connection: keep-alive\r\n"
            + "\r\n";
        var parser = new HttpParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertTrue(req.isHttp10());
        assertTrue(req.keepAlive());
    }

    @Test
    void nullOnEmptyStream() throws IOException {
        var parser = new HttpParser(new ByteArrayInputStream(new byte[0]));
        assertNull(parser.parse());
    }

    private static String headerValue(HttpParser.ParsedRequest req, String name) {
        for (var e : req.headers().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue().getFirst();
            }
        }
        return null;
    }
}
