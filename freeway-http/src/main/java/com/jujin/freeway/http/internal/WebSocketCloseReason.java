package com.jujin.freeway.http.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The WebSocket close-frame reason encoding rule: at most 123 UTF-8 bytes, cut
 * on a code-point boundary. The close control frame carries two status bytes,
 * leaving 123 of its 125-byte payload for the reason.
 *
 * <p>Both the shared session support and the built-in frame writer call this
 * one implementation, so a reason reaches the wire with the same truncation
 * no matter which path closes the connection.</p>
 */
public final class WebSocketCloseReason {

    /** RFC 6455 close payload 125 bytes minus the two status bytes. */
    public static final int MAX_BYTES = 123;

    private WebSocketCloseReason() {}

    /** The wire bytes of {@code reason}, empty for null and truncated if needed. */
    public static byte[] utf8(String reason) {
        if (reason == null) {
            return new byte[0];
        }
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return bytes;
        }
        int end = MAX_BYTES;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return Arrays.copyOf(bytes, end);
    }

    /** The reason as text, truncated on a UTF-8 code-point boundary. */
    public static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return reason;
        }
        return new String(utf8(reason), StandardCharsets.UTF_8);
    }
}
