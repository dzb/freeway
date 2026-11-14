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
import com.jujin.freeway.http.internal.HeaderStore;
import com.jujin.freeway.http.internal.HttpHeaders;
import com.jujin.freeway.http.internal.LimitedInputStream;
import com.jujin.freeway.http.internal.ResponseFraming;
import com.jujin.freeway.http.sse.SseEmitter;

/**
 * The one shared {@link HttpExchange} implementation: exchange metadata,
 * request-body limiting, response policy (status/header validation, {@code
 * Date}, {@code Content-Length}, gzip decision, {@code Vary}), coercion and
 * the send shorthands.
 *
 * <p>A transport engine composes this object — it never subclasses it. The
 * support object is created per request and discarded with it, so there is no
 * reset path and no per-request state that can leak into the next exchange.
 * The engine supplies the request bytes and the response sink through
 * {@link HttpTransport}, and the transport owns the commit fact:
 * {@link #isCommitted()} asks it rather than keeping a second flag that could
 * disagree with the wire.</p>
 */
public final class HttpExchangeSupport implements HttpExchange {

    private final HttpTransport transport;
    private final JsonCodec jsonCodec;
    private final Coercer coercer;
    private final HttpServerConfig.CompressionConfig compression;
    private final ExchangeMetaImpl exchangeMeta;
    private final RequestViewSupport requestView;
    private final HeaderStore responseHeaders = new HeaderStore();

    private volatile long maxBodySize;
    private int responseStatus = 200;

    private LimitedInputStream limitedBody;
    private byte[] cachedBody;

    /**
     * @param transport   request/response mechanism for this exchange
     * @param jsonCodec   JSON codec used by {@code json}/{@code bodyAsJson}
     * @param coercer     text coercion for typed accessors
     * @param maxBodySize the request body ceiling this exchange starts with
     * @param compression server gzip policy
     * @param correlationId incoming correlation id, or null to generate one
     */
    public HttpExchangeSupport(HttpTransport transport, JsonCodec jsonCodec,
            Coercer coercer, long maxBodySize,
            HttpServerConfig.CompressionConfig compression, String correlationId) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        this.maxBodySize = requirePositive(maxBodySize);
        this.compression = compression != null
            ? compression : HttpServerConfig.CompressionConfig.DEFAULT;
        this.exchangeMeta = new ExchangeMetaImpl(correlationId);
        this.requestView = new RequestViewSupport(
            transport.method(), transport.path(),
            transport::queryParams, transport::headers);
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
        return requestView.method();
    }

    @Override
    public String path() {
        return requestView.path();
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
        return requestView.queryParam(name);
    }

    @Override
    public List<String> queryParams(String name) {
        return requestView.queryParams(name);
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return requestView.queryParams();
    }

    @Override
    public Optional<String> header(String name) {
        return requestView.header(name);
    }

    @Override
    public List<String> headers(String name) {
        return requestView.headers(name);
    }

    @Override
    public Map<String, List<String>> headers() {
        return requestView.headers();
    }

    @Override
    public Optional<String> pathVar(String name) {
        return requestView.pathVar(name);
    }

    @Override
    public Map<String, String> pathVars() {
        return requestView.pathVars();
    }

    @Override
    public HttpExchange setPathVars(Map<String, String> vars) {
        // "Set", not "merge": this replaces the whole match, so nothing from
        // a previous route can linger even if a caller reuses this context.
        requestView.setPathVars(vars);
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
    public HttpExchange setMaxBodySize(long maxBodySize) {
        this.maxBodySize = requirePositive(maxBodySize);
        return this;
    }

    /** The body ceiling currently in force for this exchange. */
    public long bodyLimit() {
        return maxBodySize;
    }

    private LimitedInputStream limitedBody() throws IOException {
        if (limitedBody == null) {
            limitedBody = new LimitedInputStream(transport.framedBodyStream(), this::bodyLimit);
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
        if (isCommitted()) return this;
        if (status < 200 || status > 599) {
            // Informational responses are not final: a client would keep waiting
            // for the real response while this one completed the exchange.
            throw new IllegalArgumentException(
                "Invalid final response status: " + status
                    + " (1xx is informational; use 200-599)");
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
        if (isCommitted()) return this;
        HttpHeaders.requireValidName(name);
        HttpHeaders.requireValidValue(value);
        responseHeaders.set(name, value);
        return this;
    }

    @Override
    public HttpResponse addHeader(String name, String value) {
        if (isCommitted()) return this;
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
    public HttpResponse addVary(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Vary token must not be blank");
        }
        setHeader("Vary", HttpHeaders.mergeVary(responseHeader("Vary"), token));
        return this;
    }

    @Override
    public HttpResponse body(byte[] data) throws IOException {
        ResponseBody.Bytes body = new ResponseBody.Bytes(data);
        return write(body, body.length(), GzipGate.MATERIALIZED);
    }

    @Override
    public HttpResponse body(InputStream in, long contentLength) throws IOException {
        return write(new ResponseBody.Stream(in, contentLength), contentLength,
            GzipGate.STREAMING);
    }

    @Override
    public HttpResponse file(Path file, long offset, long length)
            throws IOException {
        return write(new ResponseBody.File(file, offset, length), length,
            GzipGate.STREAMING);
    }

    @Override
    public HttpResponse file(FileChannel channel, long offset, long length)
            throws IOException {
        return write(new ResponseBody.Channel(channel, offset, length), length,
            GzipGate.STREAMING);
    }

    /**
     * The one response-preparation path: commit guard, {@code Date}, body
     * suppression, gzip decision, {@code Content-Length} and {@code Vary} are
     * stated once for every body shape. The {@link GzipGate} distinguishes a
     * materialized body (the compression min-size gate is meaningful) from a
     * stream/file whose final length is not known before it is read.
     */
    private HttpResponse write(ResponseBody body, long bodyLength,
            GzipGate gzipGate) throws IOException {
        if (isCommitted()) {
            closeSkippedBody(body);
            return this;
        }
        ensureDateHeader();
        boolean bodyAllowed = allowsBody();
        if (!bodyAllowed) {
            responseHeaders.remove("Content-Length");
            closeSkippedBody(body);
            writeResponse(responseHead(), ResponseBody.Empty.INSTANCE);
            return this;
        }
        // A Content-Encoding the handler set is an encoding that already
        // happened: compressing again would double-encode the bytes and lie
        // about the length (the same applies to a non-gzip encoding).
        String existingEncoding = responseHeader("Content-Encoding");
        boolean alreadyEncoded = existingEncoding != null && !existingEncoding.isBlank();
        boolean gzip = !alreadyEncoded && (gzipGate == GzipGate.MATERIALIZED
            ? ResponseFraming.shouldGzip(compression, responseStatus, true,
                bodyLength, acceptsGzip(), compressibleContentType())
            : ResponseFraming.shouldGzipStream(compression, responseStatus, true,
                acceptsGzip(), compressibleContentType()));
        if (gzip) {
            // Compression is the transport's execution; the plan carries the
            // source body and the head states the encoding once.
            responseHeaders.remove("Content-Length");
            responseHeaders.set("Content-Encoding", "gzip");
            addVaryAcceptEncoding();
        } else if (bodyLength >= 0) {
            responseHeaders.set("Content-Length", Long.toString(bodyLength));
        } else {
            responseHeaders.remove("Content-Length");
        }
        writeResponse(responseHead(), body);
        return this;
    }

    /** A body the transport will not execute still owns an open channel. */
    private static void closeSkippedBody(ResponseBody body) throws IOException {
        if (body instanceof ResponseBody.Channel channel) {
            channel.channel().close();
        }
    }

    /** Commits one planned response and discards an uncommitted failed head. */
    private void writeResponse(ResponseHead head, ResponseBody body) throws IOException {
        try {
            transport.writeResponse(new ResponsePlan(head, body));
        } catch (IOException | RuntimeException e) {
            resetIfUncommitted();
            throw e;
        }
    }

    /** Drops a prepared response only when the transport has not written it. */
    private void resetIfUncommitted() {
        if (!transport.isCommitted()) {
            // Keep the tracing id: it belongs to the exchange, not to the
            // response that failed to materialize. Everything else on the
            // prepared head is the failed attempt's state.
            String requestId = responseHeaders.get("X-Request-Id");
            responseStatus = 200;
            responseHeaders.clear();
            if (requestId != null) {
                responseHeaders.set("X-Request-Id", requestId);
            }
        }
    }

    @Override
    public HttpResponse text(String text) throws IOException {
        if (!allowsBody()) return body(new byte[0]);
        ensureContentType(MediaTypes.TEXT_PLAIN_UTF8);
        body(text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public HttpResponse json(Object value) throws IOException {
        if (!allowsBody()) return body(new byte[0]);
        ensureContentType(MediaTypes.JSON_UTF8);
        body(jsonCodec.toJson(value).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    @Override
    public HttpResponse sendText(int status, String text) throws IOException {
        setStatus(status);
        return text(text);
    }

    @Override
    public HttpResponse sendJson(int status, Object value) throws IOException {
        setStatus(status);
        return json(value);
    }

    @Override
    public SseEmitter sse() throws IOException {
        if (isCommitted()) throw new IllegalStateException("Response already committed");
        responseHeaders.set("Content-Type", MediaTypes.EVENT_STREAM_UTF8);
        responseHeaders.set("Cache-Control", "no-cache");
        // The transport owns Connection: HTTP/1.1 forces close, HTTP/2 drops
        // the field, and a container adapter lets its engine decide.
        responseHeaders.remove("Connection");
        responseHeaders.remove("Content-Length");
        ensureDateHeader();
        try {
            return transport.openSse(responseHead());
        } catch (IOException | RuntimeException e) {
            resetIfUncommitted();
            throw e;
        }
    }

    @Override
    public boolean isCommitted() {
        // The transport is the only party that knows whether the first head
        // byte reached the wire. A support-local flag could only guess.
        return transport.isCommitted();
    }

    private ResponseHead responseHead() {
        // Framing is the transport's fact: a handler-set Transfer-Encoding
        // must never reach the wire, or the three engines would frame the same
        // response differently (the built-in writers already dropped it).
        List<ResponseHead.Header> headers = responseHeaders.entries().stream()
            .filter(entry -> !"Transfer-Encoding"
                .equalsIgnoreCase(entry.getKey()))
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

    /** Vary merge after response assembly. */
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

    /** Whether the compression min-size gate can be applied to this body. */
    private enum GzipGate {
        MATERIALIZED,
        STREAMING
    }
}
