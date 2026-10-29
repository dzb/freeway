package com.jujin.freeway.http.websocket;

import java.util.Map;

/**
 * The seam's answer to an upgrade request: the endpoint that opens the session,
 * plus the path variables the route template captured. Returned by
 * {@link com.jujin.freeway.http.ExchangeHandler#websocket(String, String, String)};
 * {@code null} from there means no route matched.
 */
public record WebSocketMatch(WebSocketEndpoint endpoint, Map<String, String> pathVariables) {
    public WebSocketMatch {
        pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
    }
}
