package com.jujin.freeway.web;

/**
 * Receives websocket frames for a single connection.
 */
public interface WebSocketListener {
    WebSocketListener NOOP = new WebSocketListener() {
    };

    default void onOpen(WebSocketSession session) throws Exception {
    }

    default void onText(String text) throws Exception {
    }

    default void onBinary(byte[] payload) throws Exception {
    }

    default void onClose(int code, String reason, boolean remote) throws Exception {
    }

    default void onError(Throwable error) {
    }
}
