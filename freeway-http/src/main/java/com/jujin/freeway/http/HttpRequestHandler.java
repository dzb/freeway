package com.jujin.freeway.http;

import com.jujin.freeway.http.websocket.WebSocketMatch;

@FunctionalInterface
public interface HttpRequestHandler {
    void handle(HttpContext ctx) throws Exception;

    default WebSocketMatch websocket(String method, String path, String origin) {
        return null;
    }
}
