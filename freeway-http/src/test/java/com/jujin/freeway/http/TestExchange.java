package com.jujin.freeway.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.internal.HttpUtils;
import com.jujin.freeway.http.sse.SseEmitter;

/**
 * A test exchange built the way an engine builds one: a {@link TestTransport}
 * for the request/response mechanism, composed with the real
 * {@link HttpExchangeSupport} for every policy decision. It implements {@link
 * HttpExchange} by delegating, so ~80 call sites keep reading {@code ctx.status()}
 * while assertions still observe the shared support object's behaviour.
 *
 * <p>This is composition (two fields plus delegation), not inheritance: no
 * framework type is subclassed, and there is no second "fake context" that
 * could bypass the policy under test.</p>
 */
public final class TestExchange implements HttpExchange {

    @SuppressWarnings("unchecked")
    private static <T> T coerce(Object input, Class<T> targetType) {
        if (input == null) {
            if (targetType.isPrimitive()) {
                throw new IllegalArgumentException(
                    "No fallback JSON coercion for primitive " +
                        targetType.getName()
                );
            }
            return null;
        }
        if (targetType.isInstance(input)) {
            return targetType.cast(input);
        }
        if (targetType == String.class) {
            return (T) String.valueOf(input);
        }
        throw new IllegalArgumentException(
            "No fallback JSON coercion for " + targetType.getName()
        );
    }

    private static final Coercer COERCER = TestExchange::coerce;

    private final TestTransport transport;
    private final HttpExchangeSupport support;

    public TestExchange() {
        this("GET", "/");
    }

    public TestExchange(String method, String path) {
        String requestPath = path;
        Map<String, List<String>> query = Map.of();
        int q = path.indexOf('?');
        if (q >= 0) {
            requestPath = path.substring(0, q);
            query = HttpUtils.parseQueryParams(path.substring(q + 1));
        }
        this.transport = new TestTransport(method, requestPath, query);
        this.support = new HttpExchangeSupport(transport,
            new JsonCodecDefault(COERCER), COERCER,
            HttpServerConfig.DEFAULT_MAX_BODY_SIZE,
            HttpServerConfig.CompressionConfig.DEFAULT, null);
    }

    // -- request configuration, before the exchange runs --

    public TestExchange requestHeader(String name, String value) {
        transport.addRequestHeader(name, value);
        return this;
    }

    public TestExchange queryParam(String name, String value) {
        transport.addQueryParam(name, value != null ? value : "");
        return this;
    }

    /** Sets the request body read by {@link #body()} (defaults to empty). */
    public TestExchange requestBody(String requestBody) {
        transport.requestBody(requestBody);
        return this;
    }

    /** Sets a binary request body. */
    public TestExchange requestBody(byte[] requestBody) {
        transport.requestBody(requestBody);
        return this;
    }

    /** Sets a streaming request body (for limiter/chunking tests). */
    public TestExchange requestBody(InputStream requestBody) {
        transport.requestBody(requestBody);
        return this;
    }

    // -- assertion accessors --

    public int status() {
        return support.status();
    }

    /** The committed response body decoded as UTF-8, or null if none. */
    public String responseBody() {
        byte[] bytes = transport.bodyBytes();
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** The committed response body as bytes, or null if none. */
    public byte[] responseBodyBytes() {
        return transport.bodyBytes();
    }

    public String responseHeader(String name) {
        return support.responseHeader(name);
    }

    /** The head the support object committed to the transport. */
    public ResponseHead responseHead() {
        return transport.head();
    }

    /** The recording transport under the support object. */
    public TestTransport transport() {
        return transport;
    }

    // -- HttpExchange: exchange metadata --

    @Override public String correlationId() { return support.correlationId(); }
    @Override public Instant startTime() { return support.startTime(); }
    @Override public Object principal() { return support.principal(); }
    @Override public void setPrincipal(Object principal) { support.setPrincipal(principal); }
    @Override public Object attribute(String key) { return support.attribute(key); }
    @Override public void setAttribute(String key, Object value) { support.setAttribute(key, value); }
    @Override public Map<String, Object> attributes() { return support.attributes(); }

    // -- HttpExchange: request side --

    @Override public String method() { return support.method(); }
    @Override public String path() { return support.path(); }
    @Override public <T> Optional<T> queryParam(String name, Class<T> type) { return support.queryParam(name, type); }
    @Override public <T> Optional<T> header(String name, Class<T> type) { return support.header(name, type); }
    @Override public Optional<String> queryParam(String name) { return support.queryParam(name); }
    @Override public List<String> queryParams(String name) { return support.queryParams(name); }
    @Override public Map<String, List<String>> queryParams() { return support.queryParams(); }
    @Override public Optional<String> header(String name) { return support.header(name); }
    @Override public List<String> headers(String name) { return support.headers(name); }
    @Override public Map<String, List<String>> headers() { return support.headers(); }
    @Override public Optional<String> pathVar(String name) { return support.pathVar(name); }
    @Override public <T> Optional<T> pathVar(String name, Class<T> type) { return support.pathVar(name, type); }
    @Override public Map<String, String> pathVars() { return support.pathVars(); }
    @Override public Optional<String> param(String name) { return support.param(name); }
    @Override public <T> Optional<T> param(String name, Class<T> type) { return support.param(name, type); }
    @Override public boolean isSecure() { return support.isSecure(); }
    @Override public SSLSession sslSession() { return support.sslSession(); }
    @Override public String remoteAddress() { return support.remoteAddress(); }
    @Override public byte[] body() throws IOException { return support.body(); }
    @Override public InputStream bodyStream() throws IOException { return support.bodyStream(); }
    @Override public String bodyText() throws IOException { return support.bodyText(); }
    @Override public <T> T bodyAsJson(Class<T> type) throws IOException { return support.bodyAsJson(type); }
    @Override public <T> T bodyAsJson(Type type) throws IOException { return support.bodyAsJson(type); }

    // -- HttpExchange: response side --

    @Override public HttpResponse setStatus(int status) { return support.setStatus(status); }
    @Override public HttpResponse setHeader(String name, String value) { return support.setHeader(name, value); }
    @Override public HttpResponse addHeader(String name, String value) { return support.addHeader(name, value); }
    @Override public HttpResponse addVary(String token) { return support.addVary(token); }
    @Override public HttpResponse body(byte[] data) throws IOException { return support.body(data); }
    @Override public HttpResponse body(InputStream in, long contentLength) throws IOException { return support.body(in, contentLength); }
    @Override public HttpResponse file(Path file, long offset, long length) throws IOException { return support.file(file, offset, length); }
    @Override public HttpResponse file(FileChannel channel, long offset, long length) throws IOException { return support.file(channel, offset, length); }
    @Override public HttpResponse text(String text) throws IOException { return support.text(text); }
    @Override public HttpResponse json(Object value) throws IOException { return support.json(value); }
    @Override public HttpResponse sendText(int status, String text) throws IOException { return support.sendText(status, text); }
    @Override public HttpResponse sendJson(int status, Object value) throws IOException { return support.sendJson(status, value); }
    @Override public SseEmitter sse() throws IOException { return support.sse(); }
    @Override public boolean isCommitted() { return support.isCommitted(); }

    // -- HttpExchange seam --

    @Override public HttpExchange setPathVars(Map<String, String> vars) { return support.setPathVars(vars); }
    @Override public HttpExchange setMaxBodySize(long maxBodySize) { return support.setMaxBodySize(maxBodySize); }

    /** The body ceiling currently in force (mirrors the support object). */
    public long bodyLimit() { return support.bodyLimit(); }

    /** The policy object under the exchange, for direct assertions. */
    public HttpExchangeSupport context() { return support; }
}
