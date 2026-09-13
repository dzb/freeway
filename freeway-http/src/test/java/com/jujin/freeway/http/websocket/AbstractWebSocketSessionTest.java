package com.jujin.freeway.http.websocket;

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
 * transport adapters): request identification, exchange metadata, and the
 * immutability of the snapshots handed to application code.
 */
class AbstractWebSocketSessionTest {

    private static final class TestSession extends AbstractWebSocketSession {
        TestSession(
            String correlationId,
            Map<String, String> pathVariables,
            Map<String, List<String>> queryParams,
            Map<String, List<String>> headers) {
            super(correlationId, "GET", "/ws/room", pathVariables, queryParams, headers);
        }

        @Override public boolean isOpen() { return true; }
        @Override public void sendText(String text) {}
        @Override public void sendBinary(byte[] data) {}
        @Override public void ping(byte[] data) {}
        @Override public void close(int code, String reason) {}
        @Override public void flush() {}

        static String reason(String value) {
            return closeReason(value);
        }
    }

    private static TestSession session() {
        return new TestSession(
            "corr-1",
            new HashMap<>(Map.of("room", "42")),
            new HashMap<>(Map.of("token", List.of("a", "b"))),
            new HashMap<>(Map.of("x-trace-id", List.of("trace-9"))));
    }

    @Test
    void exposesRequestIdentificationCaseInsensitively() {
        TestSession session = session();

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
        TestSession session = session();

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
        TestSession session =
            new TestSession("  ", Map.of(), Map.of(), Map.of());

        assertNotNull(session.correlationId());
        assertFalse(session.correlationId().isBlank());
    }

    @Test
    void snapshotsAreImmutableCopiesOfTheSourceMaps() {
        Map<String, String> pathVariables = new HashMap<>(Map.of("room", "42"));
        Map<String, List<String>> queryParams = new HashMap<>(Map.of("token", List.of("a")));
        Map<String, List<String>> headers = new HashMap<>(Map.of("x-trace-id", List.of("t")));
        TestSession session = new TestSession("c", pathVariables, queryParams, headers);

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
        TestSession session = new TestSession("c", null, null, null);

        assertEquals(Map.of(), session.pathVars());
        assertEquals(Map.of(), session.queryParams());
        assertEquals(Map.of(), session.headers());
        assertTrue(session.queryParam("token").isEmpty());
        assertTrue(session.header("x-trace-id").isEmpty());
    }

    @Test
    void closeReasonIsTruncatedToTheFramePayloadLimit() {
        assertNullSafeCloseReason("", null);
        assertEquals("bye", TestSession.reason("bye"));

        String longReason = "é".repeat(200); // 2 bytes per char → 400 bytes
        String truncated = TestSession.reason(longReason);
        assertTrue(
            truncated.getBytes(StandardCharsets.UTF_8).length <= 123,
            "a close reason must fit the 123-byte payload");
        assertFalse(
            truncated.contains("\uFFFD"),
            "the cut must land on a code-point boundary, so no replacement char appears");
        assertTrue(longReason.startsWith(truncated), "the reason is only shortened, never rewritten");

        // An ASCII reason is cut at exactly 123 bytes.
        assertEquals(123, TestSession.reason("x".repeat(200)).length());
    }

    private static void assertNullSafeCloseReason(String expected, String input) {
        assertEquals(expected, TestSession.reason(input));
    }
}
