package com.jujin.freeway.http.internal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP protocol helpers shared across the module: token validation, Vary
 * merging, and query-string parsing. These are protocol utilities, not
 * request/response API — keep {@link HttpContext} focused on the exchange.
 */
public final class HttpUtils {

    public static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";
    public static final String JSON_UTF8 = "application/json; charset=utf-8";
    public static final String EVENT_STREAM_UTF8 =
        "text/event-stream; charset=utf-8";
    public static final String OCTET_STREAM = "application/octet-stream";

    private static final DateTimeFormatter HTTP_DATE =
        DateTimeFormatter.RFC_1123_DATE_TIME;
    private static volatile long lastHttpDateSecond = Long.MIN_VALUE;
    private static volatile String cachedHttpDate = "";

    private HttpUtils() {}

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

    /** True when the Content-Type identifies JSON (case-insensitive). */
    public static boolean isJson(String contentType) {
        return contentType != null
            && contentType.toLowerCase(Locale.ROOT).contains("application/json");
    }

    /** True when the Content-Type identifies multipart/form-data. */
    public static boolean isMultipartFormData(String contentType) {
        return contentType != null
            && contentType.toLowerCase(Locale.ROOT)
                .contains("multipart/form-data");
    }

    /** True when a response Content-Type is eligible for gzip compression. */
    public static boolean isCompressibleContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
            || lower.startsWith("application/json")
            || lower.startsWith("application/javascript")
            || lower.startsWith("application/xml")
            || lower.startsWith("application/xhtml+xml")
            || lower.startsWith("image/svg+xml");
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

    /**
     * Formats an HTTP {@code Date} field value (RFC 7231 §7.1.1.2) in GMT.
     * The result is cached per second — response headers change at most once
     * per second, so the hot path avoids re-formatting on every request.
     */
    public static String httpDate(long epochMillis) {
        long second = Math.floorDiv(epochMillis, 1000L);
        if (second != lastHttpDateSecond) {
            synchronized (HttpUtils.class) {
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

    /**
     * Parses a URL query string into a parameter map.
     * Values are URL-decoded. Malformed percent-encoding is left as-is.
     */
    public static Map<String, List<String>> parseQueryParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return Map.of();
        LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? urlDecode(pair.substring(0, eq)) : urlDecode(pair);
            String value = eq >= 0 ? urlDecode(pair.substring(eq + 1)) : "";
            params.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        }
        return params;
    }

    private static String urlDecode(String text) {
        try { return URLDecoder.decode(text, StandardCharsets.UTF_8); }
        catch (Exception e) { return text; }
    }
}
