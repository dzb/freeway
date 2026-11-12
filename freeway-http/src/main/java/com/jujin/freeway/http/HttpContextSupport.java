package com.jujin.freeway.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import com.jujin.freeway.http.internal.Headers;
import com.jujin.freeway.http.internal.HttpHeaders;
import com.jujin.freeway.http.internal.LimitedInputStream;
import com.jujin.freeway.http.internal.ResponseFraming;
import com.jujin.freeway.http.sse.SseEmitter;

/**
 * The one shared {@link HttpContext} implementation: exchange metadata,
 * request-body limiting, response policy (status/header validation, {@code
 * Date}, {@code Content-Length}, gzip decision, {@code Vary}), coercion and
 * the send shorthands.
 *
 * <p>A transport engine composes this object — it never subclasses it. The
 * support object is created per request and discarded with it, so there is no
 * reset path and no per-request state that can leak into the next exchange.
 * The engine supplies only the request bytes and the response sink through
 * {@link HttpTransport}.</p>
 */
public final class HttpContextSupport implements HttpContext {

    private final HttpTransport transport;
    private final JsonCodec jsonCodec;
    private final Coercer coercer;
    private final HttpServerConfig.CompressionConfig compression;
    private final ExchangeMetaImpl exchangeMeta;
    private final Map<String, String> pathVariables = new LinkedHashMap<>(4);
    private final Headers responseHeaders = new Headers();

    private volatile long maxBodySize;
    private int responseStatus = 200;
    private boolean responded;

    private LimitedInputStream limitedBody;
    private byte[] cachedBody;

    /**
     * @param transport   request/response mechanism for this exchange
     * @param jsonCodec   JSON codec used by {@code outputJson}/{@code bodyAsJson}
     * @param coercer     text coercion for typed accessors
     * @param maxBodySize the request body ceiling this exchange starts with
     * @param compression server gzip policy
     * @param correlationId incoming correlation id, or null to generate one
     */
    public HttpContextSupport(HttpTransport transport, JsonCodec jsonCodec,
            Coercer coercer, long maxBodySize,
            HttpServerConfig.CompressionConfig compression, String correlationId) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.maxBodySize = requirePositive(maxBodySize);
        this.compression = compression != null
            ? compression : HttpServerConfig.CompressionConfig.DEFAULT;
        this.exchangeMeta = new ExchangeMetaImpl(correlationId);
    }

    private static long requirePositive(long maxBodySize) {
        if (maxBodySize <= 0) {
            throw new IllegalArgumentException("maxBodySize must be positive");
        }
        return maxBodySize;
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

    // -- request side --

    @Override
    public String method() {
        return transport.method();
    }

    @Override
    public String path() {
        return transport.path();
    }

    @Override
    public <T> Optional<T> queryParam(String name, Class<T> type) {
        return queryParam(name).map(v -> coerceText(v, type));
    }

    @Override
    public <T> Optional<T> header(String name, Class<T> type) {
        return header(name).map(v -> coerceText(v, type));
    }

    @Override
    public Optional<String> queryParam(String name) {
        List<String> values = transport.queryParams().get(name);
        return values != null && !values.isEmpty()
            ? Optional.of(values.getFirst()) : Optional.empty();
    }

    @Override
    public List<String> queryParams(String name) {
        List<String> values = transport.queryParams().get(name);
        return values != null ? values : List.of();
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return transport.queryParams();
    }

    @Override
    public Optional<String> header(String name) {
        List<String> values = requestHeaderValues(name);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
    }

    @Override
    public List<String> headers(String name) {
        return requestHeaderValues(name);
    }

    @Override
    public Map<String, List<String>> headers() {
        return transport.headers();
    }

    private List<String> requestHeaderValues(String name) {
        Map<String, List<String>> headers = transport.headers();
        List<String> direct = headers.get(name);
        if (direct != null) return direct;
        String canonical = name.toLowerCase(Locale.ROOT);
        direct = headers.get(canonical);
        if (direct != null) return direct;
        for (var entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return List.of();
    }

    @Override
    public Optional<String> pathVar(String name) {
        return Optional.ofNullable(pathVariables.get(name));
    }

    @Override
    public Map<String, String> pathVars() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(pathVariables));
    }

    @Override
    public HttpContext setPathVars(Map<String, String> vars) {
        // "Set", not "merge": this replaces the whole match, so nothing from
        // a previous route can linger even if a caller reuses this context.
        pathVariables.clear();
        if (vars != null) pathVariables.putAll(vars);
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
    public HttpContext setMaxBodySize(long maxBodySize) {
        this.maxBodySize = requirePositive(maxBodySize);
        return this;
    }

    /** The body ceiling currently in force for this exchange. */
    public long bodyLimit() {
        return maxBodySize;
    }

    private LimitedInputStream limitedBody() throws IOException {
        if (limitedBody == null) {
            limitedBody = new LimitedInputStream(transport.bodyStream(), this::bodyLimit);
        }
        return limitedBody;
    }

    @Override
    public byte[] body() throws IOException {
        if (cachedBody == null) {
            cachedBody = limitedBody().readAllBytes();
        }
        return cachedBody;
    }

    @Override
    public InputStream bodyStream() throws IOException {
        if (cachedBody != null) {
            return new ByteArrayInputStream(cachedBody);
        }
        return limitedBody();
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

    @Override
    public boolean isSecure() {
        return transport.isSecure();
    }

    @Override
    public SSLSession sslSession() {
        return transport.sslSession();
    }

    @Override
    public String remoteAddress() {
        return transport.remoteAddress();
    }

    // -- response side --

    @Override
    public HttpResponse setStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException(
                "Invalid HTTP status code: " + status + " (must be between 100 and 599)");
        }
        this.responseStatus = status;
        return this;
    }

    @Override
    public int status() {
        return responseStatus;
    }

    @Override
    public HttpResponse setHeader(String name, String value) {
        if (responded) return this;
        HttpHeaders.requireValidName(name);
        HttpHeaders.requireValidValue(value);
        responseHeaders.set(name, value);
        return this;
    }

    @Override
    public HttpResponse addHeader(String name, String value) {
        if (responded) return this;
        HttpHeaders.requireValidName(name);
        HttpHeaders.requireValidValue(value);
        responseHeaders.add(name, value);
        return this;
    }

    /** First response-header value for the name, or null. */
    public String responseHeader(String name) {
        return responseHeaders.get(name);
    }

    @Override
    public void addVary(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Vary token must not be blank");
        }
        setHeader("Vary", HttpHeaders.mergeVary(responseHeader("Vary"), token));
    }

    @Override
    public HttpResponse output(byte[] data) throws IOException {
        if (responded) return this;
        ensureDateHeader();
        boolean bodyAllowed = allowsResponseBody();
        byte[] body = data;
        if (ResponseFraming.shouldGzip(compression, responseStatus, bodyAllowed,
                body.length, acceptsGzip(), compressibleContentType())) {
            body = Compression.gzip(data);
            responseHeaders.set("Content-Encoding", "gzip");
            addVaryAcceptEncoding();
        }
        if (bodyAllowed) {
            // Framework-authoritative: the wire length is the bytes this
            // response actually carries, not whatever the handler guessed.
            responseHeaders.set("Content-Length", Integer.toString(body.length));
        } else {
            responseHeaders.remove("Content-Length");
        }
        responded = true;
        transport.writeResponse(head(), body);
        return this;
    }

    @Override
    public HttpResponse output(InputStream in, long contentLength) throws IOException {
        if (responded) return this;
        ensureDateHeader();
        boolean bodyAllowed = allowsResponseBody();
        boolean gzip = ResponseFraming.shouldGzipStream(compression, responseStatus,
            bodyAllowed, acceptsGzip(), compressibleContentType());
        if (!bodyAllowed) {
            // 204/205/304 never carry Content-Length, whatever the handler
            // or the caller passed in (RFC 9110 §8.6).
            responseHeaders.remove("Content-Length");
        } else if (gzip) {
            // The compressed length is unknown until the stream is consumed;
            // the transport chooses chunked framing / no HTTP/2 length.
            responseHeaders.remove("Content-Length");
            responseHeaders.set("Content-Encoding", "gzip");
            addVaryAcceptEncoding();
        } else if (contentLength >= 0) {
            responseHeaders.set("Content-Length", Long.toString(contentLength));
        } else {
            responseHeaders.remove("Content-Length");
        }
        responded = true;
        transport.writeResponse(head(), in, gzip);
        return this;
    }

    @Override
    public HttpResponse outputFile(Path file, long offset, long length)
            throws IOException {
        if (responded) return this;
        ensureDateHeader();
        boolean bodyAllowed = allowsResponseBody();
        boolean gzip = ResponseFraming.shouldGzipStream(compression, responseStatus,
            bodyAllowed, acceptsGzip(), compressibleContentType());
        prepareFileHead(gzip, length, bodyAllowed);
        responded = true;
        transport.writeResponseFile(head(), file, offset, length, gzip);
        return this;
    }

    @Override
    public HttpResponse outputFile(FileChannel channel, long offset, long length)
            throws IOException {
        if (responded) {
            channel.close();
            return this;
        }
        ensureDateHeader();
        boolean bodyAllowed = allowsResponseBody();
        boolean gzip = ResponseFraming.shouldGzipStream(compression, responseStatus,
            bodyAllowed, acceptsGzip(), compressibleContentType());
        prepareFileHead(gzip, length, bodyAllowed);
        responded = true;
        transport.writeResponseFile(head(), channel, offset, length, gzip);
        return this;
    }

    private void prepareFileHead(boolean gzip, long length, boolean bodyAllowed) {
        if (gzip) {
            responseHeaders.remove("Content-Length");
            responseHeaders.set("Content-Encoding", "gzip");
            addVaryAcceptEncoding();
        } else if (bodyAllowed && length >= 0) {
            responseHeaders.set("Content-Length", Long.toString(length));
        } else {
            responseHeaders.remove("Content-Length");
        }
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

    @Override
    public SseEmitter sse() throws IOException {
        if (responded) throw new IllegalStateException("Response already committed");
        responseHeaders.set("Content-Type", MediaTypes.EVENT_STREAM_UTF8);
        responseHeaders.set("Cache-Control", "no-cache");
        // The transport owns Connection: HTTP/1.1 forces close, HTTP/2 drops
        // the field, and a container adapter lets its engine decide.
        responseHeaders.remove("Connection");
        responseHeaders.remove("Content-Length");
        ensureDateHeader();
        SseEmitter emitter = transport.openSse(head());
        responded = true;
        return emitter;
    }

    @Override
    public boolean isResponded() {
        return responded;
    }

    private ResponseHead head() {
        List<ResponseHead.Header> headers = responseHeaders.entries().stream()
            .map(entry -> new ResponseHead.Header(entry.getKey(), entry.getValue()))
            .toList();
        return new ResponseHead(responseStatus,
            "HEAD".equalsIgnoreCase(transport.method()), headers);
    }

    private void ensureContentType(String contentType) {
        if (Strings.blankToNull(responseHeader("Content-Type")) == null) {
            setHeader("Content-Type", contentType);
        }
    }

    private boolean acceptsGzip() {
        return Compression.acceptsGzip(transport.headers().get("accept-encoding"));
    }

    private boolean compressibleContentType() {
        return MediaTypes.isCompressibleContentType(responseHeaders.get("Content-Type"));
    }

    /** Adds the RFC 7231 {@code Date} header unless the application set one. */
    private void ensureDateHeader() {
        if (!responseHeaders.contains("Date")) {
            responseHeaders.set("Date", HttpHeaders.httpDate(System.currentTimeMillis()));
        }
    }

    /** Vary merge after commit-time response assembly. */
    private void addVaryAcceptEncoding() {
        responseHeaders.set("Vary",
            HttpHeaders.mergeVary(responseHeaders.get("Vary"), "Accept-Encoding"));
    }

    private <T> T coerceText(String value, Class<T> type) {
        return value != null ? coercer.coerce(value, type) : null;
    }

    /** Returns the charset from the request Content-Type, defaulting to UTF-8. */
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
        try {
            return Charset.forName(charset);
        } catch (Exception e) {
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
