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

        // header names are normalized: first char upper, rest lower
        assertEquals("websocket", headerValue(req, "upgrade"));
        assertEquals("Upgrade", headerValue(req, "connection"));
        assertEquals("13", headerValue(req, "sec-websocket-version"));
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", headerValue(req, "sec-websocket-key"));
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
