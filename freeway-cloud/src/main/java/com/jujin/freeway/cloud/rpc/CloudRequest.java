package com.jujin.freeway.cloud.rpc;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Outbound HTTP request: verb + path (relative to the instance endpoint) +
 * headers + body + idempotency.
 *
 * @param method     HTTP verb (upper-cased)
 * @param path       path starting with {@code /}, resolved against the instance endpoint
 * @param headers    request headers (immutable)
 * @param body       request body, or {@code null} for body-less verbs
 * @param idempotent whether replaying this request on an ambiguous outcome
 *                   (timeout, mid-flight I/O failure, 5xx) is safe — see
 *                   {@link #idempotent()}
 */
public record CloudRequest(
    String method, String path, Map<String, String> headers, byte[] body, boolean idempotent) {

    public CloudRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method must not be blank");
        }
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/': " + path);
        }
        method = method.toUpperCase(Locale.ROOT);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** Derives idempotency from the verb. */
    public CloudRequest(String method, String path, Map<String, String> headers, byte[] body) {
        this(method, path, headers, body, idempotentVerb(method));
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

    /**
     * Explicitly marks (or unmarks) this request as safe to replay on
     * ambiguous outcomes. Use it when a non-idempotent verb carries an
     * idempotent operation — e.g. the remote-CallBus bridge forwards the
     * {@link Idempotent} marker from the consumer's interface method.
     */
    public CloudRequest idempotentWith(boolean value) {
        return new CloudRequest(method, path, headers, body, value);
    }

    /**
     * Whether replaying this request on an ambiguous outcome — the peer may
     * have applied it (timeout, mid-flight I/O failure, 5xx) — is safe.
     * Defaults to the verb's RFC 9110 class: safe and idempotent verbs
     * ({@code GET, HEAD, PUT, DELETE, OPTIONS, TRACE}) are {@code true};
     * {@code POST}, {@code PATCH} and unknown verbs are not. The resilience
     * loop retries ambiguous outcomes only for idempotent requests.
     */
    @Override
    public boolean idempotent() {
        return idempotent;
    }

    /** RFC 9110 method classification: safe verbs plus PUT/DELETE replay
     *  safely; POST, PATCH and unknown verbs do not. */
    public static boolean idempotentVerb(String method) {
        return switch (method == null ? "" : method.toUpperCase(Locale.ROOT)) {
            case "GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
    }
}
