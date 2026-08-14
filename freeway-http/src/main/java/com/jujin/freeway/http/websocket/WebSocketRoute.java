package com.jujin.freeway.http.websocket;

import java.util.Objects;

import com.jujin.freeway.http.route.PathPattern;

public record WebSocketRoute(String path, WebSocketEndpoint endpoint) {
    public WebSocketRoute {
        path = normalizePath(path);
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        PathPattern.validateRegistrationPath(path);
    }

    public static WebSocketRoute of(String path, WebSocketEndpoint endpoint) {
        return new WebSocketRoute(path, endpoint);
    }

    /**
     * @deprecated the {@code pattern} component never participated in
     *             matching (the router matches on the path via its trie);
     *             kept as a source-compatible bridge. Use
     *             {@link #of(String, WebSocketEndpoint)}.
     */
    @Deprecated
    public WebSocketRoute(String path, WebSocketEndpoint endpoint, PathPattern pattern) {
        this(path, endpoint);
    }

    private static String normalizePath(String path) {
        return PathPattern.normalizePath(path);
    }
}
