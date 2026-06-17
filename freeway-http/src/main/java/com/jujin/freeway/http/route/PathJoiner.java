package com.jujin.freeway.http.route;

public final class PathJoiner {

    private PathJoiner() {}

    /**
     * Normalizes a path segment for joining: ensures leading {@code /}, strips trailing
     * {@code /}, returns {@code ""} for the root path ({@code null}, empty, or {@code "/"}).
     */
    public static String normalize(String path) {
        if (
            path == null || (path = path.trim()).isEmpty() || "/".equals(path)
        ) {
            return "";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public static String join(String prefix, String path) {
        return normalize(prefix) + normalize(path);
    }
}
