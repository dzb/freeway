package com.jujin.freeway.web;

@FunctionalInterface
public interface WebRequestHandler {
    void handle(HttpContext ctx) throws Exception;

    default WebSocketMatch websocket(String method, String path, String origin) {
        return null;
    }
}
