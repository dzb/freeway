package com.jujin.freeway.http.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Http1xParserTest {

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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertEquals("GET", req.method());
        assertEquals("/ping", req.path());
        assertFalse(req.isUpgradeRequest());
    }

    @Test
    void rejectsInvalidHeaderName() {
        var parser = new Http1xParser(new ByteArrayInputStream(
            "GET / HTTP/1.1\r\n Bad: value\r\n\r\n".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IOException.class, parser::parse);
    }

    @Test
    void rejectsRepeatedOrNonFinalChunkedEncoding() {
        for (String value : new String[]{"chunked, chunked", "chunked, gzip"}) {
            var parser = new Http1xParser(new ByteArrayInputStream((
                "POST / HTTP/1.1\r\nTransfer-Encoding: " + value + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII)));
            assertThrows(IOException.class, parser::parse);
        }
    }

    @Test
    void parsePostWithBody() throws IOException {
        String raw = "POST /echo HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Content-Length: 4\r\n"
            + "\r\n"
            + "abcd";
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
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
        var parser = new Http1xParser(new ByteArrayInputStream(
            raw.getBytes(StandardCharsets.ISO_8859_1)));
        var req = parser.parse();

        assertNotNull(req);
        assertTrue(req.isHttp10());
        assertTrue(req.keepAlive());
    }

    @Test
    void nullOnEmptyStream() throws IOException {
        var parser = new Http1xParser(new ByteArrayInputStream(new byte[0]));
        assertNull(parser.parse());
    }

    @Test
    void rejectsDuplicateContentLength() {
        byte[] raw = ("POST / HTTP/1.1\r\nContent-Length: 4\r\n"
                + "Content-Length: 7\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
                "Duplicate Content-Length headers should be rejected");
    }

    @Test
    void transferEncodingHandlesCommaSeparatedChunked() throws Exception {
        byte[] raw = ("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        var req = parser.parse();
        assertTrue(req.isChunked(), "Transfer-Encoding: chunked should set isChunked=true");
    }

    @Test
    void transferEncodingRejectsUnknownCoding() {
        byte[] raw = ("POST / HTTP/1.1\r\nTransfer-Encoding: gzip, chunked\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
                "Unknown Transfer-Encoding should be rejected");
    }

    @Test
    void rejectsBothContentLengthAndChunked() throws Exception {
        byte[] raw = ("POST / HTTP/1.1\r\nContent-Length: 4\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse);
    }

    @Test
    void rejectsTruncatedHeaders() {
        // "GET / HTTP/1.1\r\nHost: a" — no final CRLF → truncated headers
        byte[] raw = "GET / HTTP/1.1\r\nHost: a".getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
                "Truncated headers should throw IOException");
    }

    @Test
    void rejectsTruncatedRequestLine() {
        // "GET / HTTP/1.1" with no CRLF → truncated
        byte[] raw = "GET / HTTP/1.1".getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
                "Truncated request line should throw IOException");
    }

    @Test
    void parsesPipelinedRequests() throws Exception {
        // Two requests back-to-back in one TCP segment
        byte[] raw = ("GET /a HTTP/1.1\r\n\r\nGET /b HTTP/1.1\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));

        var req1 = parser.parse();
        assertEquals("/a", req1.path());
        var req2 = parser.parse();
        assertEquals("/b", req2.path(),
                "pipelined second request should be parsed, not lost");
    }

    @Test
    void pipelinedBodyStreamConsumesOnlyOwnedBytes() throws Exception {
        // A POST whose body sits in the same buffer as the next request: the
        // body stream must stop exactly at Content-Length so the following
        // pipelined request survives in the parser buffer.
        byte[] raw = ("POST /a HTTP/1.1\r\nContent-Length: 4\r\n\r\n"
                + "bodyGET /b HTTP/1.1\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));

        var req1 = parser.parse();
        assertEquals("/a", req1.path());
        assertEquals(4, req1.contentLength());
        try (var body = parser.bodyStream(req1.contentLength())) {
            assertEquals("body", new String(body.readAllBytes(), StandardCharsets.ISO_8859_1));
        }
        var req2 = parser.parse();
        assertEquals("/b", req2.path(),
                "pipelined request after a body must survive the body read");
    }

    @Test
    void upgradeStreamPreservesFrameReadAhead() throws Exception {
        String handshake = "GET /ws HTTP/1.1\r\nHost: localhost\r\n\r\n";
        byte[] frame = {(byte) 0x81, (byte) 0x81, 1, 2, 3, 4,
            (byte) ('x' ^ 1)};
        byte[] raw = new byte[handshake.getBytes(StandardCharsets.ISO_8859_1).length + frame.length];
        System.arraycopy(handshake.getBytes(StandardCharsets.ISO_8859_1), 0, raw, 0,
            handshake.length());
        System.arraycopy(frame, 0, raw, handshake.length(), frame.length);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        parser.parse();
        assertEquals('x', parser.upgradeStream().readAllBytes()[6] ^ 1);
    }

    // ── Control-character rejection (RFC 7230 §3.2.4) ─────────────

    @Test
    void rejectsBareCrInHeaderValue() {
        byte[] raw = ("GET / HTTP/1.1\r\nX-Foo: ab\rcd\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "a bare CR inside a header value must be rejected");
    }

    @Test
    void rejectsNulInHeaderValue() {
        byte[] raw = ("GET / HTTP/1.1\r\nX-Foo: a\u0000b\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "a NUL inside a header value must be rejected");
    }

    @Test
    void rejectsControlCharacterInRequestLine() {
        byte[] raw = ("GET /\u0007x HTTP/1.1\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "a control character in the request line must be rejected");
    }

    @Test
    void rejectsBareCrAtBufferBoundaryInHeaderValue() {
        // The header line is padded so the parser's 4096-byte bulk read ends
        // exactly on a CR that is NOT followed by LF in the next chunk — the
        // crPending path must reject it rather than splicing a bare CR into
        // the header value.
        String prefix = "GET / HTTP/1.1\r\nX-Foo: " + "a".repeat(4072) + "\r";
        byte[] rest = "cd\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] raw = new byte[prefix.length() + rest.length];
        System.arraycopy(prefix.getBytes(StandardCharsets.ISO_8859_1), 0, raw,
            0, prefix.length());
        System.arraycopy(rest, 0, raw, prefix.length(), rest.length);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "a bare CR at the bulk-read boundary must be rejected");
    }

    @Test
    void obsFoldStillAccepted() throws Exception {
        byte[] raw = ("GET / HTTP/1.1\r\nHost: localhost\r\n"
                + "X-Long: part1\r\n part2\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        var req = parser.parse();
        assertNotNull(req);
        assertEquals("part1 part2", headerValue(req, "x-long"),
            "obs-fold continuation lines must keep working");
    }

    @Test
    void normalRequestUnaffectedByControlCharCheck() throws Exception {
        byte[] raw = ("GET /a?b=c HTTP/1.1\r\nHost: localhost\r\n"
                + "Accept: text/html\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        var req = parser.parse();
        assertNotNull(req);
        assertEquals("/a", req.path());
        assertEquals("b=c", req.queryString());
        assertEquals("text/html", headerValue(req, "accept"));
    }

    // ── Content-Length strict 1*DIGIT (RFC 7230 §3.3.2) ────────────

    @Test
    void rejectsSignedContentLength() {
        byte[] raw = ("POST / HTTP/1.1\r\nContent-Length: +5\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "Content-Length: +5 must be rejected");
    }

    @Test
    void rejectsNegativeContentLength() {
        byte[] raw = ("POST / HTTP/1.1\r\nContent-Length: -5\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "Content-Length: -5 must be rejected");
    }

    @Test
    void rejectsWhitespaceInsideContentLength() {
        byte[] raw = ("POST / HTTP/1.1\r\nContent-Length: 4 5\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "whitespace inside Content-Length must be rejected");
    }

    @Test
    void rejectsEmptyContentLength() {
        byte[] raw = ("POST / HTTP/1.1\r\nContent-Length:\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        assertThrows(IOException.class, parser::parse,
            "an empty Content-Length must be rejected");
    }

    @Test
    void plainDigitContentLengthStillParses() throws Exception {
        byte[] raw = ("POST / HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Length: 42\r\n\r\n")
            .getBytes(StandardCharsets.ISO_8859_1);
        var parser = new Http1xParser(new ByteArrayInputStream(raw));
        var req = parser.parse();
        assertNotNull(req);
        assertEquals(42, req.contentLength());
    }

    private static String headerValue(Http1xParser.ParsedRequest req, String name) {
        for (var e : req.headers().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue().getFirst();
            }
        }
        return null;
    }
}
