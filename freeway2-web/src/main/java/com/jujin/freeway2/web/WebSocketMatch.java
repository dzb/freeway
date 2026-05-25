package com.jujin.freeway2.web;

import java.util.Map;

public record WebSocketMatch(WebSocketEndpoint endpoint, Map<String, String> pathVariables) {
    public WebSocketMatch {
        pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
    }
}
