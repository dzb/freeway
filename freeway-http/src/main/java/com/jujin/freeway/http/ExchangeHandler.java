package com.jujin.freeway.http;

import com.jujin.freeway.http.websocket.WebSocketMatch;

/**
 * Handles incoming HTTP exchanges. Also supports optional WebSocket
 * upgrade negotiation.
 */
@FunctionalInterface
public interface ExchangeHandler {

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
     *
     * <p><b>Obligation:</b> an engine must consult this for every
     * upgrade-eligible request before completing the handshake — it is the
     * pipeline's first stage, carrying the server's WebSocket routes and the
     * CORS origin check. An engine that skips it bypasses both silently
     * (upgrades neither match the declared routes nor honor the origin
     * policy), and a null answer must abort the upgrade.
     */
    default WebSocketMatch websocket(String method, String path, String origin) {
        return null;
    }
}
