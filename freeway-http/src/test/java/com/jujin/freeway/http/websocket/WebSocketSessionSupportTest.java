package com.jujin.freeway.http.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The metadata half every WebSocket session shares (built-in engine and the
 * transport adapters): request identification, exchange metadata, the
 * immutability of the snapshots handed to application code, and the close
 * reason truncation the support object owns.
 */
class WebSocketSessionSupportTest {

    /** Records the frame operations without a socket. */
    private static final class RecordingTransport implements WebSocketTransport {
        int closeCode = -1;
        String closeReason;
        boolean open = true;

        @Override public boolean isOpen() { return open; }
        @Override public void sendText(String text) {}
        @Override public void sendBinary(byte[] data) {}
        @Override public void ping(byte[] data) {}
        @Override public void flush() {}

        @Override
        public void close(int code, String reason) {
            this.closeCode = code;
            this.closeReason = reason;
            this.open = false;
        }
    }

    private static WebSocketSessionSupport session(
        String correlationId,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers) {
        return new WebSocketSessionSupport(new RecordingTransport(),
            correlationId, "GET", "/ws/room", pathVariables, queryParams, headers);
    }

    private static WebSocketSessionSupport session() {
        return session(
            "corr-1",
            new HashMap<>(Map.of("room", "42")),
            new HashMap<>(Map.of("token", List.of("a", "b"))),
            new HashMap<>(Map.of("x-trace-id", List.of("trace-9"))));
    }

    @Test
    void exposesRequestIdentificationCaseInsensitively() {
        WebSocketSessionSupport session = session();

        assertEquals("GET", session.method());
        assertEquals("/ws/room", session.path());
        assertEquals("42", session.pathVar("room").orElseThrow());
        assertEquals(Map.of("room", "42"), session.pathVars());
        assertEquals("a", session.queryParam("token").orElseThrow());
        assertEquals(List.of("a", "b"), session.queryParams("token"));
        assertEquals(List.of(), session.queryParams("absent"));
        // Header lookup is case-insensitive on both sides.
        assertEquals("trace-9", session.header("X-Trace-Id").orElseThrow());
        assertEquals(List.of("trace-9"), session.headers("X-TRACE-ID"));
        assertEquals(List.of(), session.headers("absent"));
    }

    @Test
    void exposesExchangeMetadataAndAttributes() {
        WebSocketSessionSupport session = session();

        assertEquals("corr-1", session.correlationId());
        assertNotNull(session.startTime());
        session.setPrincipal("alice");
        assertEquals("alice", session.principal());
        session.setAttribute("tenant", "acme");
        assertEquals("acme", session.attribute("tenant"));
        assertEquals(Map.of("tenant", "acme"), session.attributes());
    }

    @Test
    void generatesACorrelationIdWhenBlank() {
        WebSocketSessionSupport session =
            session("  ", Map.of(), Map.of(), Map.of());

        assertNotNull(session.correlationId());
        assertFalse(session.correlationId().isBlank());
    }

    @Test
    void snapshotsAreImmutableCopiesOfTheSourceMaps() {
        Map<String, String> pathVariables = new HashMap<>(Map.of("room", "42"));
        Map<String, List<String>> queryParams = new HashMap<>(Map.of("token", List.of("a")));
        Map<String, List<String>> headers = new HashMap<>(Map.of("x-trace-id", List.of("t")));
        WebSocketSessionSupport session = session("c", pathVariables, queryParams, headers);

        pathVariables.put("room", "changed");
        queryParams.put("token", List.of("changed"));
        headers.put("x-trace-id", List.of("changed"));

        assertEquals("42", session.pathVar("room").orElseThrow());
        assertEquals(List.of("a"), session.queryParams("token"));
        assertEquals("t", session.header("x-trace-id").orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> session.pathVars().put("x", "y"));
        assertThrows(UnsupportedOperationException.class, () -> session.queryParams().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> session.headers().put("x", List.of()));
    }

    @Test
    void nullCollectionsBecomeEmptySnapshots() {
        WebSocketSessionSupport session = session("c", null, null, null);

        assertEquals(Map.of(), session.pathVars());
        assertEquals(Map.of(), session.queryParams());
        assertEquals(Map.of(), session.headers());
        assertTrue(session.queryParam("token").isEmpty());
        assertTrue(session.header("x-trace-id").isEmpty());
    }

    @Test
    void closeTruncatesTheReasonBeforeItReachesTheTransport() throws IOException {
        RecordingTransport transport = new RecordingTransport();
        WebSocketSessionSupport session = new WebSocketSessionSupport(transport,
            "c", "GET", "/ws", Map.of(), Map.of(), Map.of());

        session.close(1000, "bye");
        assertEquals("bye", transport.closeReason);

        String longReason = "é".repeat(200); // 2 bytes per char → 400 bytes
        session.close(1000, longReason);
        assertTrue(
            transport.closeReason.getBytes(StandardCharsets.UTF_8).length <= 123,
            "a close reason must fit the 123-byte payload");
        assertFalse(
            transport.closeReason.contains("\uFFFD"),
            "the cut must land on a code-point boundary, so no replacement char appears");
        assertTrue(longReason.startsWith(transport.closeReason),
            "the reason is only shortened, never rewritten");
    }

    @Test
    void closeReasonTruncationRules() {
        assertEquals("", WebSocketSessionSupport.truncateCloseReason(null));
        assertEquals("", WebSocketSessionSupport.truncateCloseReason(""));
        assertEquals("bye", WebSocketSessionSupport.truncateCloseReason("bye"));
        // An ASCII reason is cut at exactly 123 bytes.
        assertEquals(123,
            WebSocketSessionSupport.truncateCloseReason("x".repeat(200)).length());
    }
}
