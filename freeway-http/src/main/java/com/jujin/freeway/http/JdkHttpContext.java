package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class JdkHttpContext extends HttpContext {
    private final HttpExchange exchange;
    private final RequestContext requestContext;
    private final Map<String, List<String>> queryParams;
    private volatile byte[] cachedBody;
    private int responseStatus = 200;
    private volatile boolean responded;

    public JdkHttpContext(HttpExchange exchange, JsonCodec jsonCodec, Coercer coercer, RequestContext requestContext) {
        super(jsonCodec, coercer);
        this.exchange = exchange;
        this.requestContext = requestContext;
        this.queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
    }

    @Override
    public String method() {
        return exchange.getRequestMethod();
    }

    @Override
    public String path() {
        return exchange.getRequestURI().getPath();
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
        return exchange.getRequestHeaders().getFirst(name);
    }

    @Override
    public List<String> headers(String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values != null ? values : List.of();
    }

    @Override
    public byte[] body() throws IOException {
        if (cachedBody == null) {
            try (var is = exchange.getRequestBody()) {
                cachedBody = readBodyLimited(is);
            } catch (IOException e) {
                cachedBody = new byte[0];
                throw e;
            }
        }
        return cachedBody;
    }

    @Override
    public HttpContext status(int status) {
        this.responseStatus = status;
        return this;
    }

    @Override
    public int statusCode() {
        return responseStatus;
    }

    @Override
    public HttpContext headerSet(String name, String value) {
        if (responded) {
            return this;
        }
        exchange.getResponseHeaders().set(name, value);
        return this;
    }

    @Override
    public HttpContext output(byte[] data) throws IOException {
        if (responded) {
            return this;
        }
        boolean headRequest = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
        long length = responseStatus == 204 || responseStatus == 304 ? 0 : data.length;
        exchange.sendResponseHeaders(responseStatus, length);
        responded = true;
        try (OutputStream os = exchange.getResponseBody()) {
            if (!headRequest && data.length > 0) {
                os.write(data);
            }
        }
        return this;
    }

    @Override
    public SseEmitter sse() throws IOException {
        setupSseHeaders();
        // JDK 25: sendResponseHeaders(200, -1) does not properly switch the response
        // body stream from PlaceholderOutputStream; use 0 instead.
        exchange.sendResponseHeaders(200, 0);
        responded = true;
        return new SseEmitter(exchange.getResponseBody());
    }

    @Override
    public RequestContext requestContext() {
        return requestContext;
    }

    public static Map<String, List<String>> parseQueryParams(String rawQuery) {
        LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? decode(pair.substring(0, eq)) : decode(pair);
            String value = eq >= 0 ? decode(pair.substring(eq + 1)) : "";
            params.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return params;
    }

    private static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return text;
        }
    }
}
