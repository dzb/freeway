package com.jujin.freeway2.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class PathPattern {
    private final String template;
    private final String[] segments;
    private final String[] paramNames;
    private final Pattern[] paramPatterns;
    private final boolean wildcard;

    private static final int MAX_REGEX_LENGTH = 64;

    PathPattern(String template) {
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
                    paramNames[i] = inner;
                }
            } else {
                segments[i] = seg;
            }
        }
        this.wildcard = parsedWildcard;
    }

    String template() {
        return template;
    }

    Map<String, String> match(String path) {
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

    private static String[] splitPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return new String[0];
        }
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split("/");
    }

    private static String normalizePath(String path) {
        String value = HttpContext.blankToNull(path);
        if (value == null || "/".equals(value)) {
            return "/";
        }
        value = value.startsWith("/") ? value : "/" + value;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static boolean containsPathTraversal(String path) {
        for (String seg : path.split("/")) {
            if (isPathTraversalSegment(seg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPathTraversalSegment(String seg) {
        // literal ".." or Windows-style "..\\xxx"
        if ("..".equals(seg) || seg.startsWith("..\\")) {
            return true;
        }
        // null byte trick
        if (seg.indexOf('\0') >= 0) {
            return true;
        }
        // URL-encoded path traversal: %2e%2e%2f, %2e%2e/foo, etc.
        try {
            String decoded = URLDecoder.decode(seg, StandardCharsets.UTF_8);
            if (!decoded.equals(seg) && containsPathTraversal(decoded)) {
                return true;
            }
        } catch (IllegalArgumentException e) {
            // malformed percent encoding is suspicious
            return true;
        }
        return false;
    }
}
