package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final RequestContext requestContext;
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, List<String>> queryParams = new HashMap<>();
    private int statusCode = 200;
    private String body;

    public StubHttpContext() {
        this("GET", "/");
    }

    public StubHttpContext(String method, String path) {
        super(new JsonCodecDefault(COERCER), COERCER);
        this.method = method;
        this.path = path;
        this.requestContext = RequestContext.create();
    }

    public StubHttpContext header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int statusCode() {
        return statusCode;
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
    public String queryParam(String name) {
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
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
    public String header(String name) {
        return headers.get(name);
    }

    @Override
    public List<String> headers(String name) {
        return List.of();
    }

    @Override
    public byte[] body() throws IOException {
        return body != null
            ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : new byte[0];
    }

    @Override
    public HttpContext status(int status) {
        this.statusCode = status;
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
        return requestContext;
    }

    @Override
    public HttpContext headerSet(String name, String value) {
        headers.put(name, value);
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
