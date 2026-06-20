package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.sse.SseEmitter;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * {@link HttpContext} implementation backed by a {@link com.sun.net.httpserver.HttpExchange}.
 * Designed for use by external adapters (e.g. freeway-http-robaho) that run on
 * {@code com.sun.net.httpserver} implementations.
 */
public final class JdkHttpContext extends HttpContext {

    private final HttpExchange exchange;
    private final RequestContext requestContext;
    private final Map<String, List<String>> headers;
    private final Map<String, String> pathVariables = new LinkedHashMap<>(4);
    private String method;
    private String path;
    private Map<String, List<String>> queryParams;
    private boolean responded;
    private int responseStatus = 200;

    public JdkHttpContext(HttpExchange exchange, JsonCodec jsonCodec, Coercer coercer, RequestContext requestContext) {
        super(jsonCodec, coercer);
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.requestContext = Objects.requireNonNull(requestContext, "requestContext");
        this.headers = adaptHeaders(exchange.getRequestHeaders());
        this.method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        this.path = exchange.getRequestURI().getRawPath();
    }

    @Override
    public String method() { return method; }

    @Override
    public String path() { return path; }

    @Override
    public String pathVar(String name) {
        return pathVariables.get(name);
    }

    @Override
    public Map<String, String> pathVars() { return pathVariables; }

    @Override
    public HttpContext pathVars(Map<String, String> vars) {
        pathVariables.putAll(vars);
        return this;
    }

    @Override
    public HttpContext status(int status) {
        this.responseStatus = status;
        return this;
    }

    @Override
    public int status() { return responseStatus; }

    @Override
    public HttpContext headerSet(String name, String value) {
        validateHeaderValue(value);
        exchange.getResponseHeaders().set(name, value);
        return this;
    }

    @Override
    public String header(String name) {
        var values = headers.get(name);
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    @Override
    public List<String> headers(String name) {
        var values = headers.get(name);
        return values != null ? values : List.of();
    }

    public Map<String, List<String>> headers() { return headers; }

    @Override
    public Map<String, List<String>> queryParams() {
        if (queryParams == null) {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            queryParams = rawQuery != null ? parseQueryParams(rawQuery) : Map.of();
        }
        return queryParams;
    }

    @Override
    public String queryParam(String name) {
        var params = queryParams();
        var values = params.get(name);
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    @Override
    public List<String> queryParams(String name) {
        var params = queryParams();
        return params.getOrDefault(name, List.of());
    }

    @Override
    public RequestContext requestContext() { return requestContext; }

    @Override
    public byte[] body() throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    @Override
    public SseEmitter sse() throws IOException {
        exchange.sendResponseHeaders(HttpStatus.OK, 0);
        responded = true;
        return new SseEmitter(exchange.getResponseBody());
    }

    @Override
    public HttpContext output(byte[] data) throws IOException {
        if (responded) return this;
        responded = true;
        if (!allowsResponseBody()) {
            exchange.sendResponseHeaders(responseStatus, -1);
            return this;
        }
        exchange.sendResponseHeaders(responseStatus, data.length);
        exchange.getResponseBody().write(data);
        exchange.getResponseBody().close();
        return this;
    }

    private static Map<String, List<String>> adaptHeaders(Map<String, List<String>> raw) {
        var result = new LinkedHashMap<String, List<String>>(raw.size());
        for (var entry : raw.entrySet()) {
            result.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return result;
    }
}
