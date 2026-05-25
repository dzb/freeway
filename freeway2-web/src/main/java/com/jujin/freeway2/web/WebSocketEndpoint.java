package com.jujin.freeway2.web;

/**
 * Opens a websocket connection for a mounted route.
 */
@FunctionalInterface
public interface WebSocketEndpoint {
    WebSocketListener open(WebSocketSession session) throws Exception;
}
