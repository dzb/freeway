package com.jujin.freeway.http.websocket;

import java.io.IOException;
import java.util.List;

import com.jujin.freeway.http.ExchangeMeta;
import com.jujin.freeway.http.RequestInfo;

/**
 * Thin WebSocket session view exposed to application code.
 */
public interface WebSocketSession extends ExchangeMeta, RequestInfo {

    /** Returns true if the WebSocket connection is still open. */
    boolean isOpen();

    /** Sends a text frame. Throws IOException if the connection is closed. */
    void sendText(String text) throws IOException;

    /** Sends a binary frame. Throws IOException if the connection is closed. */
    void sendBinary(byte[] data) throws IOException;

    /** Sends a ping frame with the given payload. */
    void ping(byte[] data) throws IOException;

    /** Initiates a graceful close with the given status code and reason. */
    void close(int code, String reason) throws IOException;

    /**
     * Flushes any buffered output. Useful after batch-sending frames
     * to ensure all data reaches the wire as one TCP segment.
     */
    void flush() throws IOException;

    /**
     * Sends multiple text frames in a single flush, avoiding per-frame
     * TCP overhead. Ideal for tick data and high-frequency push.
     */
    default void sendTextBatch(List<String> texts) throws IOException {
        for (String text : texts) sendText(text);
        flush();
    }
}
