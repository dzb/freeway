package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import com.jujin.freeway.http.internal.HttpHeaders;
import com.jujin.freeway.http.internal.LimitedInputStream;

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
 * One object implements all three contracts; transports extend this and
 * implement only their transport-specific methods.
 */
public abstract class AbstractHttpContext implements HttpContext {

    protected final JsonCodec jsonCodec;
    protected final Coercer coercer;
    /** The limit in force for the current request; see {@link #setMaxBodySize}. */
    protected volatile long maxBodySize;
    /** What every request starts from — the base the current limit departs from,
     *  the way {@code Backoff.baseMillis} is the base an attempt grows from. */
    private final long baseMaxBodySize;
    protected final Map<String, String> pathVariables = new LinkedHashMap<>(4);
    private final ExchangeMetaDefault exchangeMeta;

    protected AbstractHttpContext(JsonCodec jsonCodec, Coercer coercer,
                                  long maxBodySize) {
        this(jsonCodec, coercer, null, maxBodySize);
    }

    protected AbstractHttpContext(JsonCodec jsonCodec, Coercer coercer,
                                  String correlationId, long maxBodySize) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.exchangeMeta = new ExchangeMetaDefault(correlationId);
        this.baseMaxBodySize = requirePositive(maxBodySize);
        this.maxBodySize = this.baseMaxBodySize;
    }

    private static long requirePositive(long maxBodySize) {
        if (maxBodySize <= 0) {
            throw new IllegalArgumentException("maxBodySize must be positive");
        }
        return maxBodySize;
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

    /** Resets per-request exchange metadata (principal, attributes,
     *  correlation id, start time) so a keep-alive context reused for the
     *  next request never leaks state from the previous one. */
    protected final void resetExchangeMeta() {
        exchangeMeta.reset();
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
    public HttpContext setPathVars(Map<String, String> vars) {
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
    public final HttpContext setMaxBodySize(long maxBodySize) {
        this.maxBodySize = requirePositive(maxBodySize);
        return this;
    }

    /**
     * Puts the base limit back, so a filter's adjustment does not outlive its
     * request. An engine reuses one context across a keep-alive connection and
     * calls this before each request — the same place it calls
     * {@link #resetExchangeMeta()}.
     */
    protected final void resetMaxBodySize() {
        this.maxBodySize = baseMaxBodySize;
    }

    /**
     * Reads the complete request body from the transport stream, enforcing
     * {@link #setMaxBodySize(long)}. Adapters use this shared helper so their
     * body-size accounting matches the built-in engine exactly.
     *
     * <p>It is a thin wrapper over {@link LimitedInputStream} — the same
     * limiter {@code RequestBody} runs — which is what makes "identical across
     * engines" structural rather than a claim. It used to be a second,
     * independent read loop; the two agreed on every case
     * {@code BodyLimitParityTest} explores, so nothing was observably broken,
     * but the guarantee was enforced by nothing but the two implementations
     * happening to match.
     */
    protected final byte[] readBody(InputStream input) throws IOException {
        return new LimitedInputStream(input, () -> maxBodySize).readAllBytes();
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
        setHeader("Vary", HttpHeaders.mergeVary(responseHeader("Vary"), token));
    }

    @Override
    public HttpResponse output(String text) throws IOException {
        if (!allowsResponseBody()) return output(new byte[0]);
        ensureContentType(MediaTypes.TEXT_PLAIN_UTF8);
        output(text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public HttpResponse outputJson(Object value) throws IOException {
        if (!allowsResponseBody()) return output(new byte[0]);
        ensureContentType(MediaTypes.JSON_UTF8);
        output(jsonCodec.toJson(value).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public HttpResponse send(int status, String text) throws IOException {
        setStatus(status);
        return output(text);
    }

    @Override
    public HttpResponse sendJson(int status, Object value) throws IOException {
        setStatus(status);
        return outputJson(value);
    }

    // -- protected helpers for transport implementations --

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
        if (!HttpHeaders.isToken(name)) {
            throw new IllegalArgumentException("Invalid header name: " + name);
        }
    }

    /**
     * Validates that a header value contains no control characters except
     * HTAB — the exact predicate {@code Http1xParser} enforces on inbound
     * values, so a value we would accept on the way in is also one we are
     * willing to emit on the way out (preventing header injection and
     * framing corruption) — and is fully encodable as ISO-8859-1 (the
     * charset the HTTP/1.1 writers serialize header values with — anything
     * above U+00FF would otherwise be silently replaced by '?' on the wire).
     *
     * @throws IllegalArgumentException if the value contains a forbidden
     *         control character or one that is not representable in ISO-8859-1
     */
    protected static void validateHeaderValue(String value) {
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                // Mirrors Http1xParser's inbound rule: CTLs and DEL rejected,
                // HTAB allowed (RFC 9110 field-value).
                if ((c < 0x20 && c != '\t') || c == 0x7F) {
                    throw new IllegalArgumentException(
                        "Header value must not contain control characters (offending char at index "
                            + i + "): " +
                        value.substring(0, Math.min(i + 10, value.length())) + "...");
                }
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c > 0xFF) {
                    throw new IllegalArgumentException(
                        "Header value must be ISO-8859-1 encodable (non-Latin-1 character at index "
                            + i + "): " +
                        value.substring(0, Math.min(i + 10, value.length())) + "...");
                }
            }
        }
    }

    /** Sets up standard SSE response headers. */
    protected void setupSseHeaders() {
        setHeader("Content-Type", MediaTypes.EVENT_STREAM_UTF8);
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
        // A quoted parameter value is the same charset: charset="ISO-8859-1"
        // must not fall back to UTF-8 (and mojibake the body).
        if (charset.length() > 1 && charset.startsWith("\"") && charset.endsWith("\"")) {
            charset = charset.substring(1, charset.length() - 1).trim();
        }
        try { return Charset.forName(charset); } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Validates that the request Content-Type is JSON before a typed body
     * read. Accepts {@code application/json} and structured syntax suffixes
     * ({@code application/*+json}, e.g. {@code application/vnd.api+json});
     * anything else is a client error ({@link UnsupportedMediaTypeException})
     * mapped to 415 by the default error handler, not a 500.
     */
    private void checkJsonContentType() {
        String ct = header("Content-Type").orElse(null);
        if (!MediaTypes.isJson(ct)) {
            throw new UnsupportedMediaTypeException("Expected application/json Content-Type");
        }
    }
}
