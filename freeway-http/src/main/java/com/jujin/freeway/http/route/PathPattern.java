package com.jujin.freeway.http.route;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Path parsing and validation helpers shared by route registration, static
 * resource mounts, and health checks. Pure static utility — the matching
 * itself lives in {@link RouteIndex}.
 */
public final class PathPattern {

    static final int MAX_REGEX_LENGTH = 64;

    /**
     * Upper bound on the decoded length of a single request path segment
     * that is allowed to reach regex-constrained matching. Enforced before
     * any {@code Pattern.matcher(...).matches()} call so an attacker-supplied
     * segment cannot drive catastrophic backtracking (ReDoS) on a
     * developer-registered constraint pattern — the regex itself is bounded
     * to {@value #MAX_REGEX_LENGTH} chars, but the input it runs against is
     * attacker-controlled and unbounded without this cap.
     */
    public static final int MAX_SEGMENT_LENGTH = 1024;

    private PathPattern() {}

    public static void validateRegistrationPath(String path) {
        String normalized = PathPattern.normalizePath(path);
        for (String seg : PathPattern.splitPath(normalized)) {
            if (seg.isEmpty()) {
                throw new IllegalArgumentException(
                    "Path must not contain empty segments (path: " + path + ")");
            }
            if (PathPattern.isPathTraversalSegment(seg)) {
                throw new IllegalArgumentException(
                    "Path must not contain traversal segments (path: " + path + ")");
            }
            if (seg.startsWith("{") && !seg.endsWith("}")) {
                throw new IllegalArgumentException(
                    "Unclosed parameter — missing '}': " + seg + " (path: " + path + ")");
            }
            if (seg.startsWith("{") && seg.endsWith("}")) {
                String inner = seg.substring(1, seg.length() - 1);
                int colon = inner.indexOf(':');
                String name = colon >= 0 ? inner.substring(0, colon) : inner;
                if (name.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Empty parameter name in path: " + path);
                }
            }
        }
    }

    /**
     * Decodes one percent-encoded path segment, keeping '+' as a literal
     * (it is only form-encoding in query strings, not in paths). Returns
     * {@code null} for malformed encodings.
     */
    public static String decodeSegment(String seg) {
        try {
            return URLDecoder.decode(
                seg.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String normalizePath(String path) {
        String value = path == null || path.isBlank() ? null : path;
        if (value == null || "/".equals(value)) return "/";
        value = value.startsWith("/") ? value : "/" + value;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static String[] splitPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) return new String[0];
        int start = 0, end = path.length();
        if (path.charAt(0) == '/') start = 1;
        if (end > start && path.charAt(end - 1) == '/') end--;
        if (start >= end) return new String[0];

        // Count segments to size the array in one pass
        int count = 1;
        for (int i = start; i < end; i++) {
            if (path.charAt(i) == '/') count++;
        }
        String[] segs = new String[count];
        int idx = 0, segStart = start;
        for (int i = start; i <= end; i++) {
            if (i == end || path.charAt(i) == '/') {
                segs[idx++] = path.substring(segStart, i);
                segStart = i + 1;
            }
        }
        return segs;
    }

    static boolean containsPathTraversal(String path) {
        for (String seg : path.split("/")) {
            if (isPathTraversalSegment(seg)) return true;
        }
        return false;
    }

    static boolean isPathTraversalSegment(String seg) {
        if ("..".equals(seg) || seg.startsWith("..\\")) return true;
        if (seg.contains("\0")) return true;
        try {
            String decoded = URLDecoder.decode(seg, StandardCharsets.UTF_8);
            if (!decoded.equals(seg) && containsPathTraversal(decoded)) return true;
        } catch (IllegalArgumentException e) {
            return true;
        }
        return false;
    }
}
