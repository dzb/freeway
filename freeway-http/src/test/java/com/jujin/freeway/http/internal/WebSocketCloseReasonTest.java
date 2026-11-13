package com.jujin.freeway.http.internal;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketCloseReasonTest {

    @Test
    void nullAndShortReasonsRoundTrip() {
        assertEquals("", WebSocketCloseReason.truncate(null));
        assertEquals(0, WebSocketCloseReason.utf8(null).length);
        assertEquals("bye", WebSocketCloseReason.truncate("bye"));
        assertEquals("bye", new String(
            WebSocketCloseReason.utf8("bye"), StandardCharsets.UTF_8));
    }

    @Test
    void asciiReasonIsCutAtThePayloadLimit() {
        assertEquals(WebSocketCloseReason.MAX_BYTES,
            WebSocketCloseReason.utf8("x".repeat(200)).length);
        assertEquals(WebSocketCloseReason.MAX_BYTES,
            WebSocketCloseReason.truncate("x".repeat(200)).length());
    }

    @Test
    void multibyteReasonIsCutOnACodePointBoundary() {
        String reason = "é".repeat(200); // two UTF-8 bytes each
        byte[] bytes = WebSocketCloseReason.utf8(reason);
        String text = WebSocketCloseReason.truncate(reason);

        assertTrue(bytes.length <= WebSocketCloseReason.MAX_BYTES);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length
            <= WebSocketCloseReason.MAX_BYTES);
        assertFalse(text.contains("\uFFFD"),
            "the cut must not produce a replacement character");
        assertTrue(reason.startsWith(text),
            "the reason is shortened, never rewritten");
    }
}
