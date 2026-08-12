package com.jujin.freeway.http;

import com.jujin.freeway.http.websocket.WebSocketMatch;

/**
 * Handles incoming HTTP exchanges. Also supports optional WebSocket
 * upgrade negotiation.
 */
@FunctionalInterface
public interface HttpRequestHandler {

    /**
     * Processes an incoming HTTP exchange.
     *
     * @throws Exception any exception is caught and mapped to a response
     *                   by the registered exception mappers
     */
    void handle(HttpContext ctx) throws Exception;

    /**
     * Optional WebSocket upgrade negotiation. Returns a match if the
     * given parameters should be upgraded to a WebSocket connection,
     * or null to reject the upgrade.
     */
    default WebSocketMatch websocket(String method, String path, String origin) {
        return null;
    }
}
