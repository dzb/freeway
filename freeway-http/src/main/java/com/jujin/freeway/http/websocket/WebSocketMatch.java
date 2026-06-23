package com.jujin.freeway.http.websocket;

import java.util.Map;

public record WebSocketMatch(WebSocketEndpoint endpoint, Map<String, String> pathVariables) {
    public WebSocketMatch {
        pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
    }
}
