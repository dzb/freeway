package com.jujin.freeway.http.websocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.jujin.freeway.http.ExchangeMetaImpl;
import com.jujin.freeway.http.internal.HttpHeaders;

/**
 * The one shared {@link WebSocketSession} implementation: the
 * request-identification half (correlation id, start time, principal,
 * attributes) plus the method/path/path variables/query parameters/headers
 * captured at upgrade time, and delegation of the frame operations to a
 * {@link WebSocketTransport}.
 *
 * <p>An engine composes this object — it never subclasses it. Maps are
 * copied defensively: the accessors return immutable snapshots, so a caller
 * cannot mutate session state through them, and later engine-side changes to
 * the source collections are not observed.</p>
 */
public final class WebSocketSessionSupport implements WebSocketSession {

    private final WebSocketTransport transport;
    private final ExchangeMetaImpl exchangeMeta;
    private final String method;
    private final String path;
    private final Map<String, String> pathVariables;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> headers;

    /**
     * @param transport     engine-specific frame operations
     * @param correlationId the exchange correlation id; blank or null generates one
     * @param method the HTTP method of the upgrade request
     * @param path the request path
     * @param pathVariables path variables matched by the route, or null
     * @param queryParams decoded query parameters, or null
     * @param headers request headers, or null
     */
    public WebSocketSessionSupport(
        WebSocketTransport transport,
        String correlationId,
        String method,
        String path,
        Map<String, String> pathVariables,
        Map<String, List<String>> queryParams,
        Map<String, List<String>> headers
    ) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.exchangeMeta = new ExchangeMetaImpl(correlationId);
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

    // -- exchange metadata --

    @Override
    public String correlationId() {
        return exchangeMeta.correlationId();
    }

    @Override
    public Instant startTime() {
        return exchangeMeta.startTime();
    }

    @Override
    public Object principal() {
        return exchangeMeta.principal();
    }

    @Override
    public void setPrincipal(Object principal) {
        exchangeMeta.setPrincipal(principal);
    }

    @Override
    public Object attribute(String key) {
        return exchangeMeta.attribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        exchangeMeta.setAttribute(key, value);
    }

    @Override
    public Map<String, Object> attributes() {
        return exchangeMeta.attributes();
    }

    // -- request snapshot --

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Optional<String> pathVar(String name) {
        return Optional.ofNullable(pathVariables.get(name));
    }

    @Override
    public Map<String, String> pathVars() {
        return pathVariables;
    }

    @Override
    public Optional<String> queryParam(String name) {
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty()
            ? Optional.of(values.getFirst())
            : Optional.empty();
    }

    @Override
    public List<String> queryParams(String name) {
        return queryParams.getOrDefault(name, List.of());
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return queryParams;
    }

    @Override
    public Optional<String> header(String name) {
        return Optional.ofNullable(HttpHeaders.headerValue(headers, name));
    }

    @Override
    public List<String> headers(String name) {
        return HttpHeaders.headerValues(headers, name);
    }

    @Override
    public Map<String, List<String>> headers() {
        return headers;
    }

    // -- frame operations, delegated to the transport --

    @Override
    public boolean isOpen() {
        return transport.isOpen();
    }

    @Override
    public void sendText(String text) throws IOException {
        transport.sendText(text);
    }

    @Override
    public void sendBinary(byte[] data) throws IOException {
        transport.sendBinary(data);
    }

    @Override
    public void ping(byte[] data) throws IOException {
        transport.ping(data);
    }

    /**
     * Truncates the reason to the 123-byte payload the WebSocket close frame
     * allows, cutting on a UTF-8 code-point boundary: a naive byte cut can
     * leave a half character, whose replacement char re-encodes to three
     * bytes and pushes the payload back over the limit.
     */
    @Override
    public void close(int code, String reason) throws IOException {
        transport.close(code, truncateCloseReason(reason));
    }

    @Override
    public void flush() throws IOException {
        transport.flush();
    }

    @Override
    public void sendTextBatch(List<String> texts) throws IOException {
        transport.sendTextBatch(texts);
    }

    static String truncateCloseReason(String reason) {
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
