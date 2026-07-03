package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.jujin.freeway.http.sse.SseEmitter;

public final class StubHttpContext extends HttpContext {

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

    private static final Coercer COERCER = StubHttpContext::coerce;

    private final String method;
    private final String path;
    private RequestContext requestContext;
    private final Map<String, List<String>> requestHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<String>> queryParams = new LinkedHashMap<>();
    private int status = 200;
    private String body;

    public StubHttpContext() {
        this("GET", "/");
    }

    public StubHttpContext(String method, String path) {
        super(new JsonCodecDefault(COERCER), COERCER);
        this.method = method;
        int q = path.indexOf('?');
        if (q >= 0) {
            this.path = path.substring(0, q);
            this.queryParams.putAll(parseQueryParams(path.substring(q + 1)));
        } else {
            this.path = path;
        }
    }

    public StubHttpContext requestHeader(String name, String value) {
        requestHeaders.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        return this;
    }

    public StubHttpContext queryParam(String name, String value) {
        Objects.requireNonNull(name, "name");
        queryParams.computeIfAbsent(name, k -> new ArrayList<>()).add(
            value != null ? value : "");
        return this;
    }

    public int status() {
        return status;
    }

    public String responseBody() {
        return body;
    }

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public java.util.Optional<String> queryParam(String name) {
        List<String> values = queryParams.get(name);
        return (values != null && !values.isEmpty())
                ? java.util.Optional.of(values.get(0)) : java.util.Optional.empty();
    }

    @Override
    public List<String> queryParams(String name) {
        List<String> vals = queryParams.get(name);
        return vals != null ? List.copyOf(vals) : List.of();
    }

    @Override
    public Map<String, List<String>> queryParams() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        queryParams.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<String, List<String>> headers() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        requestHeaders.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public java.util.Optional<String> header(String name) {
        List<String> values = requestHeaders.get(name);
        return (values != null && !values.isEmpty())
                ? java.util.Optional.of(values.get(0)) : java.util.Optional.empty();
    }

    @Override
    public List<String> headers(String name) {
        List<String> v = requestHeaders.get(name);
        return v != null ? List.copyOf(v) : List.of();
    }

    @Override
    public String responseHeader(String name) {
        return responseHeaders.get(name);
    }

    @Override
    public byte[] body() throws IOException {
        return body != null
            ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : new byte[0];
    }

    @Override
    public HttpContext status(int status) {
        this.status = status;
        return this;
    }

    @Override
    public SseEmitter sse() {
        throw new UnsupportedOperationException(
            "SSE not supported in StubHttpContext"
        );
    }

    @Override
    public RequestContext requestContext() {
        if (requestContext == null) {
            requestContext = HttpContext.createRequestContext(header("X-Request-Id").orElse(null));
        }
        return requestContext;
    }

    @Override
    public HttpContext headerSet(String name, String value) {
        validateHeaderValue(value);
        responseHeaders.put(name, value);
        return this;
    }

    @Override
    public HttpContext output(byte[] data) {
        if (!allowsResponseBody()) {
            this.body = "";
            return this;
        }
        this.body = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        return this;
    }
}
