package com.jujin.freeway.http;

import java.util.Objects;

public record WebSocketRoute(String path, WebSocketEndpoint endpoint) {
    public WebSocketRoute {
        path = normalizePath(path);
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        PathPattern.validateRegistrationPath(path);
    }

    public static WebSocketRoute of(String path, WebSocketEndpoint endpoint) {
        return new WebSocketRoute(path, endpoint);
    }

    PathPattern pattern() {
        return new PathPattern(path);
    }

    private static String normalizePath(String path) {
        return PathPattern.normalizePath(path);
    }
}
