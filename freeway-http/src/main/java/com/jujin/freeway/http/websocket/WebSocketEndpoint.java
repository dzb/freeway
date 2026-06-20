package com.jujin.freeway.http.websocket;

/**
 * Opens a WebSocket connection for a mounted route.
 */
@FunctionalInterface
public interface WebSocketEndpoint {
    /**
     * Invoked when a new WebSocket connection is established. Returns a
     * listener that will receive frames and lifecycle events for the session.
     */
    WebSocketListener open(WebSocketSession session) throws Exception;
}
