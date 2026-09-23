package com.jujin.freeway.http.websocket;

import java.util.Objects;

import com.jujin.freeway.http.route.PathPattern;

public record WebSocketRoute(String path, WebSocketEndpoint endpoint) {
    public WebSocketRoute {
        path = PathPattern.normalizePath(path);
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
        return new WebSocketRoute(path, new LazyEndpoint(endpointType));
    }

}
