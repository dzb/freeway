package com.jujin.freeway.http.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * HTTP header helpers: the wire rules for header names and values, plus
 * lookup in a parsed header map.
 *
 * <p>Everything here is one RFC's worth of grammar — {@code tchar} for names,
 * {@code Vary} merging, {@code Host}/{@code :authority} validity, and the
 * {@code Date} field format — so a change to header syntax lands in one file.
 * Query-string parsing is not a header and lives in {@link HttpUtils}.
 */
public final class HttpHeaders {

    private static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.RFC_1123_DATE_TIME;
    private static volatile long lastHttpDateSecond = Long.MIN_VALUE;
    private static volatile String cachedHttpDate = "";

    private HttpHeaders() {}

    /** RFC 7230 §3.2.6 tchar: true when every character is an HTTP token
     *  character. Keeps the case-insensitive header store's hash/equals
     *  contract consistent (both are defined for ASCII tokens). */
    public static boolean isToken(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 'A' || c > 'Z') && (c < 'a' || c > 'z')
                    && (c < '0' || c > '9')
                    && "!#$%&'*+-.^_`|~".indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Refuses a header name that is not a non-empty RFC 7230 token.
     *
     * @throws IllegalArgumentException if the name is null or not a token
     */
    public static void requireValidName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Header name must not be null");
        }
        if (!isToken(name)) {
            throw new IllegalArgumentException("Invalid header name: " + name);
        }
    }

    /**
     * Refuses a header value containing control characters other than HTAB,
     * or one that is not fully encodable as ISO-8859-1.
     *
     * <p>The control-character rule is the exact predicate {@code
     * Http1xParser} enforces on inbound values, so a value accepted on the way
     * in is one the framework is willing to emit on the way out (preventing
     * header injection and framing corruption). ISO-8859-1 is the charset the
     * HTTP/1.1 writers serialize header values with; anything above U+00FF
     * would otherwise be silently replaced by {@code ?} on the wire.</p>
     *
     * @throws IllegalArgumentException if the value contains a forbidden
     *         control character or one that is not representable in ISO-8859-1
     */
    public static void requireValidValue(String value) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Mirrors Http1xParser's inbound rule: CTLs and DEL rejected,
            // HTAB allowed (RFC 9110 field-value).
            if ((c < 0x20 && c != '\t') || c == 0x7F) {
                throw new IllegalArgumentException(
                    "Header value must not contain control characters (offending char at index "
                        + i + "): " + value.substring(0, Math.min(i + 10, value.length())) + "...");
            }
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c > 0xFF) {
                throw new IllegalArgumentException(
                    "Header value must be ISO-8859-1 encodable (non-Latin-1 character at index "
                        + i + "): " + value.substring(0, Math.min(i + 10, value.length())) + "...");
            }
        }
    }

    /** Merges a token into an existing {@code Vary} value (or returns the
     *  token alone), without duplicating it — case-insensitive. */
    public static String mergeVary(String current, String token) {
        if (current == null || current.isBlank()) {
            return token;
        }
        for (String part : current.split(",")) {
            if (token.equalsIgnoreCase(part.trim())) {
                return current;
            }
        }
        return current + ", " + token;
    }

    /** True when {@code value} is not a valid single Host / :authority value:
     *  blank, or containing a character RFC 7230 §5.4 / RFC 7540 §8.1.2.3
     *  forbid (@, whitespace, /, \, comma, or any control character).
     *  Shared by the HTTP/1.1 Host check and the HTTP/2 :authority check. */
    public static boolean invalidHostValue(String value) {
        if (value == null || value.isBlank()) return true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ' ' || c == ','
                    || c == '/' || c == '\\' || c == '@') {
                return true;
            }
        }
        return false;
    }

    /**
     * Formats an HTTP {@code Date} field value (RFC 7231 §7.1.1.2) in GMT.
     * The result is cached per second — response headers change at most once
     * per second, so the hot path avoids re-formatting on every request.
     */
    public static String httpDate(long epochMillis) {
        long second = Math.floorDiv(epochMillis, 1000L);
        if (second != lastHttpDateSecond) {
            synchronized (HttpHeaders.class) {
                if (second != lastHttpDateSecond) {
                    cachedHttpDate = HTTP_DATE.format(
                        ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC));
                    lastHttpDateSecond = second;
                }
            }
        }
        return cachedHttpDate;
    }

    /**
     * Returns the first value of a header in a parsed header map, or null
     * when absent. Header names are case-insensitive.
     */
    public static String headerValue(Map<String, List<String>> headers,
                                     String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                    && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    /**
     * Returns all values of a header in a parsed header map, or an empty
     * list when absent. Header names are case-insensitive.
     */
    public static List<String> headerValues(Map<String, List<String>> headers,
                                            String name) {
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return List.copyOf(entry.getValue());
            }
        }
        return List.of();
    }
}