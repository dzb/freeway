package com.jujin.freeway.http.route;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.jujin.freeway.http.HttpContext;

public final class PathPattern {
    private final String template;
    private final String[] segments;
    private final String[] paramNames;
    private final Pattern[] paramPatterns;
    private final boolean wildcard;

    static final int MAX_REGEX_LENGTH = 64;

    public PathPattern(String template) {
        this.template = normalizePath(template);
        String[] raw = splitPath(this.template);
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
            } else {
                segments[i] = seg;
            }
        }
        this.wildcard = parsedWildcard;
    }

    public static void validateRegistrationPath(String path) {
        String normalized = normalizePath(path);
        for (String seg : splitPath(normalized)) {
            if (seg.isEmpty()) {
                throw new IllegalArgumentException(
                    "Path must not contain empty segments (path: " + path + ")");
            }
            if (isPathTraversalSegment(seg)) {
                throw new IllegalArgumentException(
                    "Path must not contain traversal segments (path: " + path + ")");
            }
            if (seg.startsWith("{") && !seg.endsWith("}")) {
                throw new IllegalArgumentException(
                    "Unclosed parameter — missing '}': " + seg + " (path: " + path + ")");
            }
        }
    }

    String template() {
        return template;
    }

    public Map<String, String> match(String path) {
        String[] input = splitPath(path);
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
                    if (remainder.isEmpty() || containsPathTraversal(remainder)) {
                        return null;
                    }
                    vars.put(paramNames[i], remainder);
                } else {
                    if (input[i].isEmpty() || isPathTraversalSegment(input[i])) {
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

    static String[] splitPath(String path) {
        return com.jujin.freeway.commons.util.IoUtils.splitPath(path);
    }

    public static String normalizePath(String path) {
        return com.jujin.freeway.commons.util.IoUtils.normalizePath(path);
    }

    static boolean containsPathTraversal(String path) {
        return com.jujin.freeway.commons.util.IoUtils.containsPathTraversal(path);
    }

    static boolean isPathTraversalSegment(String seg) {
        return containsPathTraversal(seg);
    }
}
