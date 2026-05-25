package com.jujin.freeway.web;

import java.util.Objects;

final class PathGroupSupport {
    private PathGroupSupport() {
    }

    static String normalizePrefix(String prefix) {
        String value = HttpContext.blankToNull(prefix);
        if (value == null || "/".equals(value)) {
            return "";
        }
        value = value.startsWith("/") ? value : "/" + value;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static String normalizePath(String path) {
        String value = Objects.requireNonNull(path, "path").trim();
        if (value.isEmpty() || "/".equals(value)) {
            return "";
        }
        value = value.startsWith("/") ? value : "/" + value;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static String join(String prefix, String path) {
        return normalizePrefix(prefix) + normalizePath(path);
    }
}
