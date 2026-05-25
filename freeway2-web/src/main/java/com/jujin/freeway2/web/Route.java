package com.jujin.freeway2.web;

import java.util.Objects;

public record Route(
    String method,
    String path,
    RouteHandler handler
) {
    public Route {
        method = normalizeMethod(method);
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        validatePath(path);
    }

    private static void validatePath(String path) {
        // reject path traversal in route registration
        for (String seg : path.split("/")) {
            if ("..".equals(seg)) {
                throw new IllegalArgumentException(
                    "Path must not contain '..' (path traversal): " + path);
            }
        }
    }

    public static Route of(String method, String path, RouteHandler handler) {
        return new Route(method, path, handler);
    }

    public static Route get(String path, RouteHandler handler) {
        return of("GET", path, handler);
    }

    public static Route post(String path, RouteHandler handler) {
        return of("POST", path, handler);
    }

    public static Route put(String path, RouteHandler handler) {
        return of("PUT", path, handler);
    }

    public static Route delete(String path, RouteHandler handler) {
        return of("DELETE", path, handler);
    }

    public static Route patch(String path, RouteHandler handler) {
        return of("PATCH", path, handler);
    }

    public static Route head(String path, RouteHandler handler) {
        return of("HEAD", path, handler);
    }

    public static Route options(String path, RouteHandler handler) {
        return of("OPTIONS", path, handler);
    }

    private static String normalizeMethod(String method) {
        return Objects.requireNonNull(method, "method").trim().toUpperCase();
    }
}
