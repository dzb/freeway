package com.jujin.freeway.http.websocket;

/**
 * Receives WebSocket frames and lifecycle event for a single connection.
 * Implement one or more callback methods.
 */
public interface WebSocketListener {
    /** No-op listener that ignores all event. */
    WebSocketListener NOOP = new WebSocketListener() {};

    /** Called when the WebSocket connection has been established. The
     *  provided session can be used to send frames. */
    default void onOpen(WebSocketSession session) throws Exception {}

    /** Called when a text frame is received from the remote peer. */
    default void onText(String text) throws Exception {}

    /** Called when a binary frame is received from the remote peer. */
    default void onBinary(byte[] payload) throws Exception {}

    /**
     * Called when the WebSocket connection is closing or has been closed.
     *
     * @param code   the closure status code
     * @param reason the closure reason
     * @param remote true if the closure was initiated by the remote peer
     */
    default void onClose(int code, String reason, boolean remote) throws Exception {}

    /**
     * Called when a pong frame arrives — the answer to a
     * {@link WebSocketSession#ping(byte[]) ping} this side sent.
     *
     * <p>The engine answers inbound pings itself, so a listener only sees the
     * pong for a ping <em>it</em> issued: the callback exists for liveness
     * checks (send a ping, require the pong within a budget), which the
     * transport cannot decide for the application — how long is "too long" is
     * the connection owner's policy, not the frame layer's.</p>
     *
     * @param payload the pong payload, echoing the ping's
     */
    default void onPong(byte[] payload) {}

    /** Called when an error occurs on the WebSocket connection. */
    default void onError(Throwable error) {}
}
