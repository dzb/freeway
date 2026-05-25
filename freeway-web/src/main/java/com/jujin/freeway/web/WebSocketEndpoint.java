package com.jujin.freeway.web;

/**
 * Opens a websocket connection for a mounted route.
 */
@FunctionalInterface
public interface WebSocketEndpoint {
    WebSocketListener open(WebSocketSession session) throws Exception;
}
