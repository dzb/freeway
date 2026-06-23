package com.jujin.freeway.http.route;

public final class PathJoiner {

    private PathJoiner() {}

    /**
     * Normalizes a path segment for joining: ensures leading {@code /}, strips trailing
     * {@code /}, returns {@code ""} for the root path ({@code null}, empty, or {@code "/"}).
     */
    public static String normalize(String path) {
        String result = PathPattern.normalizePath(path);
        return "/".equals(result) ? "" : result;
    }

    public static String join(String prefix, String path) {
        return normalize(prefix) + normalize(path);
    }
}
