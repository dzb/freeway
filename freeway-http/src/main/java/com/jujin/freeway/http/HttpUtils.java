package com.jujin.freeway.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP protocol helpers shared across the module: token validation, Vary
 * merging, and query-string parsing. These are protocol utilities, not
 * request/response API — keep {@link HttpContext} focused on the exchange.
 */
public final class HttpUtils {

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
