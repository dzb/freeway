package com.jujin.freeway.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.sse.SseEmitter;

public final class StubHttpContext extends AbstractHttpContext {

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
    private final Map<String, List<String>> requestHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, String> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<String>> queryParams = new LinkedHashMap<>();
    private int status = 200;
    private String body;
    private String requestBody;

    public StubHttpContext() {
        this("GET", "/");
    }

    public StubHttpContext(String method, String path) {
        super(new JsonCodecDefault(COERCER), COERCER);
        this.method = method;
        int q = path.indexOf('?');
        if (q >= 0) {
            this.path = path.substring(0, q);
            this.queryParams.putAll(
                HttpUtils.parseQueryParams(path.substring(q + 1)));
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

    /** Sets the request body read by {@link #body()} (defaults to empty). */
    public StubHttpContext requestBody(String requestBody) {
        this.requestBody = requestBody;
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
    public Optional<String> queryParam(String name) {
        List<String> values = queryParams.get(name);
        return (values != null && !values.isEmpty())
                ? Optional.of(values.get(0)) : Optional.empty();
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
    public Optional<String> header(String name) {
        List<String> values = requestHeaders.get(name);
        return (values != null && !values.isEmpty())
                ? Optional.of(values.get(0)) : Optional.empty();
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
        if (requestBody != null) {
            return requestBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return body != null
            ? body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            : new byte[0];
    }

    @Override
    public HttpResponse status(int status) {
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
    public HttpResponse setHeader(String name, String value) {
        validateHeaderValue(value);
        responseHeaders.put(name, value);
        return this;
    }

    @Override
    public HttpResponse output(byte[] data) {
        if (!allowsResponseBody()) {
            this.body = "";
            return this;
        }
        this.body = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public HttpResponse output(InputStream in, long contentLength) throws IOException {
        if (!allowsResponseBody()) {
            this.body = "";
            return this;
        }
        this.body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return this;
    }

    @Override
    public HttpResponse outputFile(Path file, long offset, long length)
            throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new IOException("stub cannot buffer files larger than 2GB");
        }
        try (InputStream in = Files.newInputStream(file)) {
            in.skipNBytes(offset);
            byte[] data = new byte[(int) length];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return output(data);
        }
    }
}
