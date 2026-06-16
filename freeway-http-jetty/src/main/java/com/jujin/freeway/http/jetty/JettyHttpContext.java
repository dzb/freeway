package com.jujin.freeway.http.jetty;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;

final class JettyHttpContext extends HttpContext {

    private final Request request;
    private final Response response;
    private final Callback callback;
    private final RequestContext requestContext;
    private final Map<String, List<String>> queryParams;
    private volatile byte[] cachedBody;
    private int responseStatus = 200;
    private volatile boolean responded;

    JettyHttpContext(
        Request request,
        Response response,
        JsonCodec jsonCodec,
        Coercer coercer,
        RequestContext requestContext,
        Callback callback
    ) {
        super(jsonCodec, coercer);
        this.request = Objects.requireNonNull(request, "request");
        this.response = Objects.requireNonNull(response, "response");
        this.callback = Objects.requireNonNull(callback, "callback");
        this.requestContext = Objects.requireNonNull(
            requestContext,
            "requestContext"
        );
        this.queryParams = parseQueryParams(request);
    }

    @Override
    public String method() {
        return request.getMethod() != null ? request.getMethod() : "";
    }

    @Override
    public String path() {
        String path =
            request.getHttpURI() != null
                ? request.getHttpURI().getPath()
                : null;
        return path != null ? path : "/";
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
        return request.getHeaders().get(name);
    }

    @Override
    public List<String> headers(String name) {
        List<String> values = request.getHeaders().getValuesList(name);
        return values != null ? List.copyOf(values) : List.of();
    }

    @Override
    public byte[] body() throws IOException {
        if (cachedBody == null) {
            try (InputStream input = Request.asInputStream(request)) {
                cachedBody = readBodyLimited(input);
            }
        }
        return cachedBody;
    }

    @Override
    public SseEmitter sse() throws IOException {
        response.setStatus(200);
        setupSseHeaders();
        responded = true;
        return new SseEmitter(
            new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[] { (byte) b }, 0, 1);
                }

                @Override
                public void write(byte[] b, int off, int len)
                    throws IOException {
                    if (len == 0) return;
                    response.write(
                        true,
                        ByteBuffer.wrap(b, off, len),
                        Callback.NOOP
                    );
                }

                @Override
                public void flush() {
                    // Jetty flushes internally via write(true, ...)
                }

                @Override
                public void close() {
                    callback.succeeded();
                }
            }
        );
    }

    @Override
    public RequestContext requestContext() {
        return requestContext;
    }

    @Override
    public HttpContext status(int status) {
        this.responseStatus = status;
        response.setStatus(status);
        return this;
    }

    @Override
    public int statusCode() {
        return responseStatus;
    }

    boolean responded() {
        return responded;
    }

    @Override
    public HttpContext headerSet(String name, String value) {
        validateHeaderValue(value);
        response.getHeaders().put(name, value);
        return this;
    }

    @Override
    public HttpContext output(byte[] data) throws IOException {
        if (responded) {
            return this;
        }
        boolean headRequest = "HEAD".equalsIgnoreCase(method());
        if (!headRequest && responseStatus != 204 && responseStatus != 304) {
            response
                .getHeaders()
                .put(HttpHeader.CONTENT_LENGTH, String.valueOf(data.length));
        }
        responded = true;
        if (
            headRequest ||
            responseStatus == 204 ||
            responseStatus == 304 ||
            data.length == 0
        ) {
            callback.succeeded();
            return this;
        }
        response.write(true, ByteBuffer.wrap(data), callback);
        return this;
    }

    private static Map<String, List<String>> parseQueryParams(Request request) {
        Fields fields = Request.extractQueryParameters(request);
        LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
        for (Fields.Field field : fields) {
            params.put(field.getName(), List.copyOf(field.getValues()));
        }
        return Map.copyOf(params);
    }
}
