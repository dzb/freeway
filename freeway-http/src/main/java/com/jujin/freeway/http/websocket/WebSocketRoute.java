package com.jujin.freeway.http.websocket;

import java.util.Objects;

import com.jujin.freeway.http.route.PathPattern;

/**
 * One WebSocket mount: a path template and the endpoint that opens sessions for
 * it. The class form is resolved by the HTTP module at startup, like an HTTP
 * route's class handler.
 */
public record WebSocketRoute(String path, WebSocketEndpoint endpoint) {
    public WebSocketRoute {
        path = PathPattern.normalize(path);
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        PathPattern.validateRegistrationPath(path);
    }

    public static WebSocketRoute of(String path, WebSocketEndpoint endpoint) {
        return new WebSocketRoute(path, endpoint);
    }

    /** Creates a route from an endpoint class — the wrapper is resolved by
     *  {@code HttpModule} at startup, before the first upgrade. */
    public static WebSocketRoute of(
        String path,
        Class<? extends WebSocketEndpoint> endpointType
    ) {
        return new WebSocketRoute(path, new ResolvableEndpoint(endpointType));
    }

}
