package com.jujin.freeway.http.websocket;

import java.util.Set;

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

    /**
     * The WebSocket subprotocols this endpoint supports (RFC 6455 §4.1).
     * During the upgrade handshake the server selects the first protocol the
     * client offered that appears in this set; if none match, no
     * {@code Sec-WebSocket-Protocol} header is returned.
     */
    default Set<String> subprotocols() {
        return Set.of();
    }
}
