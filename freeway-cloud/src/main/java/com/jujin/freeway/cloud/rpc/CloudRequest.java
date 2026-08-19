package com.jujin.freeway.cloud.rpc;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 * Outbound HTTP request: verb + path (relative to the instance endpoint) +
 * headers + body.
 *
 * @param method  HTTP verb (upper-cased)
 * @param path    path starting with {@code /}, resolved against the instance endpoint
 * @param headers request headers (immutable)
 * @param body    request body, or {@code null} for body-less verbs
 */
public record CloudRequest(String method, String path, Map<String, String> headers, byte[] body) {

    public CloudRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/': " + path);
        }
        method = method.toUpperCase(java.util.Locale.ROOT);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static CloudRequest get(String path) {
        return new CloudRequest("GET", path, Map.of(), null);
    }

    public static CloudRequest post(String path, byte[] body, String contentType) {
        return new CloudRequest("POST", path, Map.of("Content-Type", contentType), body);
    }

    public static CloudRequest post(String path, String jsonBody) {
        return new CloudRequest("POST", path, Map.of("Content-Type", "application/json"),
            jsonBody.getBytes(StandardCharsets.UTF_8));
    }
}
