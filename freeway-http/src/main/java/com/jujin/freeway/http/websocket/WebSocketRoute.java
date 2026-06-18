package com.jujin.freeway.http.websocket;

import java.util.Objects;
import com.jujin.freeway.http.route.PathPattern;

public record WebSocketRoute(String path, WebSocketEndpoint endpoint, PathPattern pattern) {
    public WebSocketRoute {
        path = normalizePath(path);
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        PathPattern.validateRegistrationPath(path);
        pattern = new PathPattern(path);
    }

    public WebSocketRoute(String path, WebSocketEndpoint endpoint) {
        this(path, endpoint, new PathPattern(normalizePath(path)));
    }

    public static WebSocketRoute of(String path, WebSocketEndpoint endpoint) {
        return new WebSocketRoute(path, endpoint);
    }

    private static String normalizePath(String path) {
        return com.jujin.freeway.commons.util.IoUtils.normalizePath(path);
    }
}
