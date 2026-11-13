package com.jujin.freeway.http.websocket;

import java.io.IOException;
import java.util.List;

/**
 * Transport SPI for WebSocket frames: the engine-specific send/close
 * operations. An engine implements this interface and composes
 * {@link WebSocketSessionSupport}; it never subclasses a framework type.
 *
 * <p>The close reason arrives already truncated to the 123-byte close-frame
 * payload by {@link WebSocketSessionSupport#close(int, String)}.</p>
 */
public interface WebSocketTransport {

    /** Returns true if the WebSocket connection is still open. */
    boolean isOpen();

    /** Sends a text frame. Throws IOException if the connection is closed. */
    void sendText(String text) throws IOException;

    /** Sends a binary frame. Throws IOException if the connection is closed. */
    void sendBinary(byte[] data) throws IOException;

    /** Sends a ping frame with the given payload. */
    void ping(byte[] data) throws IOException;

    /**
     * Initiates a graceful close with the given status code and reason.
     *
     * @param code   RFC 6455 close status code
     * @param reason reason already truncated to the 123-byte payload limit
     */
    void close(int code, String reason) throws IOException;

    /**
     * Flushes any buffered output. Useful after batch-sending frames
     * to ensure all data reaches the wire as one TCP segment.
     */
    void flush() throws IOException;

    /**
     * Sends multiple text frames in a single flush, avoiding per-frame
     * TCP overhead. The default implementation sends sequentially; an
     * engine with a true batch write overrides it.
     */
    default void sendTextBatch(List<String> texts) throws IOException {
        for (String text : texts) sendText(text);
        flush();
    }
}
