package com.jujin.freeway.http.undertow;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.sse.SseEmitter;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HttpString;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class UndertowHttpContext extends HttpContext {

    private final HttpServerExchange exchange;
    private final RequestContext requestContext;
    private final Map<String, List<String>> queryParams;
    private volatile byte[] cachedBody;
    private int responseStatus = 200;
    private volatile boolean responded;

    UndertowHttpContext(
        HttpServerExchange exchange,
        JsonCodec jsonCodec,
        Coercer coercer,
        RequestContext requestContext
    ) {
        super(jsonCodec, coercer);
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.requestContext = Objects.requireNonNull(
            requestContext,
            "requestContext"
        );
        this.queryParams = parseQueryParams(exchange.getQueryParameters());
    }

    @Override
    public String method() {
        return exchange.getRequestMethod() != null
            ? exchange.getRequestMethod().toString()
            : "";
    }

    @Override
    public String path() {
        String relative = exchange.getRelativePath();
        return relative != null ? relative : "/";
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
        List<String> values = new ArrayList<>();
        for (String value : exchange.getRequestHeaders().get(name)) {
            values.add(value);
        }
        return List.copyOf(values);
    }

    @Override
    public byte[] body() throws IOException {
        if (cachedBody == null) {
            try (InputStream in = exchange.getInputStream()) {
                cachedBody = readBodyLimited(in);
            }
        }
        return cachedBody;
    }

    @Override
    public SseEmitter sse() throws IOException {
        exchange.setStatusCode(200);
        setupSseHeaders();
        exchange.startBlocking();
        responded = true;
        return new SseEmitter(exchange.getOutputStream());
    }

    @Override
    public RequestContext requestContext() {
        return requestContext;
    }

    @Override
    public HttpContext status(int status) {
        this.responseStatus = status;
        exchange.setStatusCode(status);
        return this;
    }

    @Override
    public int statusCode() {
        return responseStatus;
    }

    @Override
    public HttpContext headerSet(String name, String value) {
        validateHeaderValue(value);
        exchange.getResponseHeaders().put(new HttpString(name), value);
        return this;
    }

    @Override
    public HttpContext output(byte[] data) throws IOException {
        if (responded) {
            return this;
        }
        boolean headRequest = "HEAD".equalsIgnoreCase(method());
        if (!headRequest && responseStatus != 204 && responseStatus != 304) {
            exchange.setResponseContentLength(data.length);
        }
        responded = true;
        try (OutputStream os = exchange.getOutputStream()) {
            if (
                !headRequest &&
                responseStatus != 204 &&
                responseStatus != 304 &&
                data.length > 0
            ) {
                os.write(data);
            }
        }
        return this;
    }

    private static Map<String, List<String>> parseQueryParams(
        Map<String, Deque<String>> source
    ) {
        LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<String>> entry : source.entrySet()) {
            params.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(params);
    }
}
