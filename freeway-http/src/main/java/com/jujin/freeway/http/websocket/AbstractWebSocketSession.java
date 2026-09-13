package com.jujin.freeway.http.websocket;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.jujin.freeway.http.ExchangeMetaDefault;
import com.jujin.freeway.http.internal.HttpUtils;

/**
 * Shared request-identification half of a {@link WebSocketSession}: the
 * correlation id, start time, principal and attributes, plus the method, path,
 * path variables, query parameters and headers captured at upgrade time. An
 * implementation supplies those values once and then only has to implement the
 * engine-specific frame operations ({@link #isOpen()}, {@link #sendText},
 * {@link #sendBinary}, {@link #ping}, {@link #close}, {@link #flush}).
 *
 * <p>Kept in the core so the built-in engine and the transport adapters share
 * one implementation of the accessors — the same division as
 * {@link com.jujin.freeway.http.AbstractHttpContext} for HTTP exchanges. Maps
 * are copied defensively: the accessors return immutable snapshots, so a caller
 * cannot mutate session state through them, and later engine-side changes to
 * the source collections are not observed.
 */
public abstract class AbstractWebSocketSession implements WebSocketSession {

    private final ExchangeMetaDefault exchangeMeta;
    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> headers;

    /**
     * @param correlationId the exchange correlation id; blank or null generates one
     * @param method the HTTP method of the upgrade request
     * @param path the request path
     * @param pathVariables path variables matched by the route, or null
     * @param queryParams decoded query parameters, or null
     * @param headers request headers, or null
     */
    protected AbstractWebSocketSession(
        String correlationId,
        String method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers
    ) {
        this.exchangeMeta = new ExchangeMetaDefault(correlationId);
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.pathVariables = pathVariables == null ? Map.of() : Map.copyOf(pathVariables);
        this.queryParams = snapshot(queryParams);
        this.headers = snapshot(headers);
    }

    /** Copies the map and each value list, so the snapshot cannot be mutated. */
    private static Map<String, List<String>> snapshot(Map<String, List<String>> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, List<String>> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, values) ->
            copy.put(key, values == null ? List.of() : List.copyOf(values)));
        return Map.copyOf(copy);
    }

    @Override
    public final String correlationId() {
        return exchangeMeta.correlationId();
    }

    @Override
    public final Instant startTime() {
        return exchangeMeta.startTime();
    }

    @Override
    public final Object principal() {
        return exchangeMeta.principal();
    }

    @Override
    public final void setPrincipal(Object principal) {
        exchangeMeta.setPrincipal(principal);
    }

    @Override
    public final Object attribute(String key) {
        return exchangeMeta.attribute(key);
    }

    @Override
    public final void setAttribute(String key, Object value) {
        exchangeMeta.setAttribute(key, value);
    }

    @Override
    public final Map<String, Object> attributes() {
        return exchangeMeta.attributes();
    }

    @Override
    public final String method() {
        return method;
    }

    @Override
    public final String path() {
        return path;
    }

    @Override
    public final Optional<String> pathVar(String name) {
        return Optional.ofNullable(pathVariables.get(name));
    }

    @Override
    public final Map<String, String> pathVars() {
        return pathVariables;
    }

    @Override
    public final Optional<String> queryParam(String name) {
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty()
            ? Optional.of(values.getFirst())
            : Optional.empty();
    }

    @Override
    public final List<String> queryParams(String name) {
        return queryParams.getOrDefault(name, List.of());
    }

    @Override
    public final Map<String, List<String>> queryParams() {
        return queryParams;
    }

    @Override
    public final Optional<String> header(String name) {
        return Optional.ofNullable(HttpUtils.headerValue(headers, name));
    }

    @Override
    public final List<String> headers(String name) {
        return HttpUtils.headerValues(headers, name);
    }

    @Override
    public final Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Truncates a close reason to the 123-byte payload the WebSocket close frame
     * allows, cutting on a UTF-8 code-point boundary: a naive byte cut can leave
     * a half character, whose replacement char re-encodes to three bytes and
     * pushes the payload back over the limit.
     */
    protected static String closeReason(String reason) {
        if (reason == null) return "";
        byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 123) return reason;
        int end = 123;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8);
    }
}
