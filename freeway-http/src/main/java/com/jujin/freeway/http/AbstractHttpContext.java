package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.http.body.BodyTooLargeException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Base class for {@link HttpContext} implementations. Owns the exchange
 * metadata, the shared state (codecs, body-size limit, path variables), and
 * the convenience logic that does not belong to a specific transport
 * (coercion, JSON body helpers, Vary merging, text/JSON send shorthands).
 * One object implements all three faces; the combined view exposes them via
 * {@link #request()} and {@link #response()}.
 */
public abstract class AbstractHttpContext
        implements HttpContext, HttpRequest, HttpResponse {

    protected final JsonCodec jsonCodec;
    protected final Coercer coercer;
    protected volatile long maxBodySize = 10_485_760L;
    protected volatile boolean bodyLimitExceeded;
    protected final Map<String, String> pathVariables = new LinkedHashMap<>(4);
    private final ExchangeMetaDefault exchangeMeta;

    protected AbstractHttpContext(JsonCodec jsonCodec, Coercer coercer) {
        this(jsonCodec, coercer, null);
    }

    protected AbstractHttpContext(JsonCodec jsonCodec, Coercer coercer,
                                  String correlationId) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.exchangeMeta = new ExchangeMetaDefault(correlationId);
    }

    /** Returns the current response header value for the given name, or null. */
    protected abstract String responseHeader(String name);

    // -- exchange metadata --

    @Override
    public String correlationId() {
        return exchangeMeta.correlationId();
    }

    /** Replaces the correlation id for a reused exchange (keep-alive
     *  connections process a new request per reset). Blank input keeps the
     *  existing id. */
    protected final void setCorrelationId(String correlationId) {
        exchangeMeta.setCorrelationId(correlationId);
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

    @Override
    public HttpRequest request() {
        return this;
    }

    @Override
    public HttpResponse response() {
        return this;
    }

    // -- transport-specific methods, declared here so the convenience
    // defaults on HttpContext do not clash with the abstract contract on
    // HttpRequest/HttpResponse (a class inheriting both must state them) --

    @Override
    public abstract Optional<String> queryParam(String name);

    @Override
    public abstract Optional<String> header(String name);

    @Override
    public abstract byte[] body() throws IOException;

    @Override
    public abstract HttpResponse status(int status);

    @Override
    public abstract HttpResponse setHeader(String name, String value);

    @Override
    public abstract HttpResponse output(byte[] data) throws IOException;

    // -- shared request logic --

    @Override
    public <T> Optional<T> queryParam(String name, Class<T> type) {
        return queryParam(name).map(v -> coerceText(v, type));
    }

    @Override
    public <T> Optional<T> header(String name, Class<T> type) {
        return header(name).map(v -> coerceText(v, type));
    }

    @Override
    public Optional<String> pathVar(String name) {
        return Optional.ofNullable(pathVariables.get(name));
    }

    @Override
    public Map<String, String> pathVars() {
        return Collections.unmodifiableMap(pathVariables);
    }

    @Override
    public HttpRequest pathVars(Map<String, String> vars) {
        this.pathVariables.putAll(vars);
        return this;
    }

    @Override
    public <T> Optional<T> pathVar(String name, Class<T> type) {
        return pathVar(name).map(v -> coerceText(v, type));
    }

    @Override
    public Optional<String> param(String name) {
        return queryParam(name).or(() -> pathVar(name));
    }

    @Override
    public <T> Optional<T> param(String name, Class<T> type) {
        return param(name).map(v -> coerceText(v, type));
    }

    @Override
    public HttpRequest maxBodySize(long maxBodySize) {
        if (maxBodySize <= 0) {
            throw new IllegalArgumentException("maxBodySize must be positive");
        }
        this.maxBodySize = maxBodySize;
        return this;
    }

    @Override
    public String bodyText() throws IOException {
        return new String(body(), charsetFromContentType());
    }

    @Override
    public <T> T bodyAsJson(Class<T> type) throws IOException {
        return bodyAsJson((Type) type);
    }

    @Override
    public <T> T bodyAsJson(Type type) throws IOException {
        checkJsonContentType();
        @SuppressWarnings("unchecked")
        T value = (T) jsonCodec.fromJson(bodyText(), type);
        return value;
    }

    // -- shared response logic --

    @Override
    public void addVary(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Vary token must not be blank");
        }
        setHeader("Vary", HttpUtils.mergeVary(responseHeader("Vary"), token));
    }

    @Override
    public HttpResponse output(String text) throws IOException {
        if (!allowsResponseBody()) return output(new byte[0]);
        ensureContentType("text/plain; charset=utf-8");
        output(text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public HttpResponse outputJson(Object value) throws IOException {
        if (!allowsResponseBody()) return output(new byte[0]);
        ensureContentType("application/json; charset=utf-8");
        output(jsonCodec.toJson(value).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public HttpResponse send(int status, String text) throws IOException {
        status(status);
        return output(text);
    }

    @Override
    public HttpResponse sendJson(int status, Object value) throws IOException {
        status(status);
        return outputJson(value);
    }

    // -- protected helpers for transport implementations --

    /**
     * Reads the request body, enforcing the configured max body size.
     *
     * @throws BodyTooLargeException if the body exceeds maxBodySize
     */
    protected final byte[] readBodyLimited(InputStream input) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (total > maxBodySize - read) {
                bodyLimitExceeded = true;
                throw new BodyTooLargeException(maxBodySize);
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    /** Coerces a string value to the given target type. */
    protected final <T> T coerceText(String value, Class<T> type) {
        return value != null ? coercer.coerce(value, type) : null;
    }

    /**
     * Validates that a header name is a non-empty RFC 7230 token.
     *
     * @throws IllegalArgumentException if the name is not a token
     */
    protected static void validateHeaderName(String name) {
        if (name == null) throw new IllegalArgumentException("Header name must not be null");
        if (!HttpUtils.isToken(name)) {
            throw new IllegalArgumentException("Invalid header name: " + name);
        }
    }

    /**
     * Validates that a header value does not contain CR or LF characters,
     * preventing HTTP response header injection.
     *
     * @throws IllegalArgumentException if the value contains CR or LF
     */
    protected static void validateHeaderValue(String value) {
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n') {
                    throw new IllegalArgumentException(
                        "Header value must not contain CR or LF: " +
                        value.substring(0, Math.min(i + 10, value.length())) + "...");
                }
            }
        }
    }

    /** Sets up standard SSE response headers. */
    protected void setupSseHeaders() {
        setHeader("Content-Type", "text/event-stream; charset=utf-8");
        setHeader("Cache-Control", "no-cache");
        setHeader("Connection", "keep-alive");
    }

    /** Sets Content-Type if not already present (text/json output helpers). */
    private void ensureContentType(String contentType) {
        if (Strings.blankToNull(responseHeader("Content-Type")) == null) {
            setHeader("Content-Type", contentType);
        }
    }

    /** Returns the charset from the Content-Type header, defaulting to UTF-8. */
    private Charset charsetFromContentType() {
        String ct = header("Content-Type").orElse(null);
        if (ct == null) return StandardCharsets.UTF_8;
        int idx = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) return StandardCharsets.UTF_8;
        String charset = ct.substring(idx + 8).trim();
        int semi = charset.indexOf(';');
        if (semi >= 0) charset = charset.substring(0, semi).trim();
        try { return Charset.forName(charset); } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private void checkJsonContentType() {
        String ct = header("Content-Type").orElse(null);
        if (ct == null || !ct.toLowerCase(Locale.ROOT).contains("application/json")) {
            throw new IllegalStateException("Expected application/json Content-Type");
        }
    }

}
