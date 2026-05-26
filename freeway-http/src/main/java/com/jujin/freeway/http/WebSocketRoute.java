package com.jujin.freeway.http;

import java.util.Objects;

public record WebSocketRoute(String path, WebSocketEndpoint endpoint) {
    public WebSocketRoute {
        path = normalizePath(path);
        endpoint = Objects.requireNonNull(endpoint, "endpoint");
        validatePath(path);
    }

    private static void validatePath(String path) {
        for (String seg : path.split("/")) {
            if ("..".equals(seg)) {
                throw new IllegalArgumentException(
                    "Path must not contain '..' (path traversal): " + path);
            }
        }
    }

    public static WebSocketRoute of(String path, WebSocketEndpoint endpoint) {
        return new WebSocketRoute(path, endpoint);
    }

    PathPattern pattern() {
        return new PathPattern(path);
    }

    private static String normalizePath(String path) {
        String value = HttpContext.blankToNull(path);
        if (value == null || "/".equals(value)) {
            return "/";
        }
        value = value.startsWith("/") ? value : "/" + value;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
