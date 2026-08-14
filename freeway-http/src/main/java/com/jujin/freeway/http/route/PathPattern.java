package com.jujin.freeway.http.route;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * A single path template: literal segments, path parameters
 * ({@code {name}} / {@code :name}), regex-constrained parameters
 * ({@code {name:regex}}) and terminal wildcards ({@code {path:.*}}).
 *
 * <p>This class provides <b>single-template matching</b> for callers that
 * need to test one path against one pattern outside the route table.
 * {@link RouteIndex} deliberately does <i>not</i> use it — the router keeps
 * its own trie-based matcher (O(segments) independent of route count) with
 * the same segment semantics. Both share the static utilities here
 * ({@link #splitPath}, {@link #decodeSegment}, {@link #normalizePath},
 * {@link #validateRegistrationPath}, traversal checks and the length caps).
 */
public final class PathPattern {
    private final String template;
    private final String[] segments;
    private final String[] paramNames;
    private final Pattern[] paramPatterns;
    private final boolean wildcard;

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

    public PathPattern(String template) {
        this.template = PathPattern.normalizePath(template);
        String[] raw = PathPattern.splitPath(this.template);
        this.segments = new String[raw.length];
        this.paramNames = new String[raw.length];
        this.paramPatterns = new Pattern[raw.length];
        boolean parsedWildcard = false;
        for (int i = 0; i < raw.length; i++) {
            String seg = raw[i];
            if (seg.startsWith("{") && seg.endsWith("}")) {
                String inner = seg.substring(1, seg.length() - 1);
                int colon = inner.indexOf(':');
                if (colon >= 0) {
                    String name = inner.substring(0, colon);
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException(
                            "Empty parameter name in path: " + template);
                    }
                    String regex = inner.substring(colon + 1);
                    if (regex.length() > MAX_REGEX_LENGTH) {
                        throw new IllegalArgumentException(
                            "Regex constraint too long (max " + MAX_REGEX_LENGTH + " chars): '" + regex + "' in path: " + template);
                    }
                    paramNames[i] = name;
                    if (".*".equals(regex) && i == raw.length - 1) {
                        parsedWildcard = true;
                    } else {
                        try {
                            paramPatterns[i] = Pattern.compile(regex);
                        } catch (PatternSyntaxException e) {
                            throw new IllegalArgumentException(
                                "Invalid regex constraint '" + regex + "' for param '" + name + "' in path: " + template, e);
                        }
                    }
                } else {
                    if (inner.isEmpty()) {
                        throw new IllegalArgumentException(
                            "Empty parameter name in path: " + template);
                    }
                    paramNames[i] = inner;
                }
            } else if (seg.startsWith(":") && seg.length() > 1) {
                paramNames[i] = seg.substring(1);
            } else {
                segments[i] = seg;
            }
        }
        this.wildcard = parsedWildcard;
    }

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

    String template() {
        return template;
    }

    public Map<String, String> match(String path) {
        String[] raw = PathPattern.splitPath(path);
        String[] input = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            input[i] = PathPattern.decodeSegment(raw[i]);
            if (input[i] == null) {
                return null; // malformed percent-encoding
            }
            if (input[i].length() > MAX_SEGMENT_LENGTH) {
                return null; // overlong segment — never run regexes on it
            }
        }
        if (wildcard) {
            if (input.length < segments.length) {
                return null;
            }
        } else if (input.length != segments.length) {
            return null;
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < segments.length; i++) {
            if (segments[i] == null) {
                if (wildcard && i == segments.length - 1) {
                    String remainder = String.join("/", Arrays.copyOfRange(input, i, input.length));
                    if (remainder.isEmpty() || PathPattern.containsPathTraversal(remainder)) {
                        return null;
                    }
                    vars.put(paramNames[i], remainder);
                } else {
                    if (input[i].isEmpty() || PathPattern.isPathTraversalSegment(input[i])) {
                        return null;
                    }
                    Pattern p = paramPatterns[i];
                    if (p != null && !p.matcher(input[i]).matches()) {
                        return null;
                    }
                    vars.put(paramNames[i], input[i]);
                }
            } else if (!segments[i].equals(input[i])) {
                return null;
            }
        }
        return vars;
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
