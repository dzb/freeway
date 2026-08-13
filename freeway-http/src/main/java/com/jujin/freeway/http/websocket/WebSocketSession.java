package com.jujin.freeway.http.websocket;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.jujin.freeway.http.ExchangeMeta;

/**
 * Thin WebSocket session view exposed to application code.
 */
public interface WebSocketSession extends ExchangeMeta {

    /** Returns the HTTP method used to establish the WebSocket upgrade. */
    String method();

    /** Returns the request path of the WebSocket upgrade. */
    String path();

    /** Returns the value of a path variable by name. */
    Optional<String> pathVar(String name);

    /** Returns an unmodifiable map of all path variables. */
    Map<String, String> pathVars();

    /** Returns the first query parameter value for the given name. */
    Optional<String> queryParam(String name);

    /** Returns all query parameter values for the given name, or an empty list. */
    List<String> queryParams(String name);

    /** Returns an unmodifiable map of all query parameters. */
    Map<String, List<String>> queryParams();

    /** Returns the first header value for the given name. */
    Optional<String> header(String name);

    /** Returns all header values for the given name, or an empty list. */
    List<String> headers(String name);

    /** Returns an unmodifiable map of all headers. */
    Map<String, List<String>> headers();

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
