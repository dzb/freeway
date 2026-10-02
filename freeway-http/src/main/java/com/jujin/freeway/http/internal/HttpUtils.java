package com.jujin.freeway.http.internal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * URL query-string parsing. Not a header — the request-target's query
 * component, decoded into a parameter map.
 *
 * <p>Header wire rules live in {@link HttpHeaders}.
 */
public final class HttpUtils {

    private HttpUtils() {}

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