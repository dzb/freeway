package com.jujin.freeway.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.jujin.freeway.commons.scalar.Coercer;
import com.jujin.freeway.commons.scalar.DefaultCoercer;

public abstract class HttpContext {
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?i)\\bcharset=([^\\s;]+)");

    private Map<String, String> pathVariables = Map.of();
    private MultipartForm cachedMultipart;
    protected final JsonCodec jsonCodec;
    protected volatile long maxBodySize = 10_485_760L;

    protected HttpContext(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec;
    }

    public void pathVariables(Map<String, String> vars) {
        this.pathVariables = vars != null ? Map.copyOf(vars) : Map.of();
    }

    public String pathVar(String name) {
        return pathVariables.get(name);
    }

    public Map<String, String> pathVars() {
        return pathVariables;
    }

    public <T> T pathVar(String name, Class<T> type) {
        return coerceText(pathVar(name), type);
    }

    public abstract String method();

    public abstract String path();

    public abstract String queryParam(String name);

    public abstract List<String> queryParams(String name);

    public abstract Map<String, List<String>> queryParams();

    public <T> T queryParam(String name, Class<T> type) {
        return coerceText(queryParam(name), type);
    }

    public abstract String header(String name);

    public abstract List<String> headers(String name);

    public <T> T header(String name, Class<T> type) {
        return coerceText(header(name), type);
    }

    public abstract byte[] body() throws IOException;

    public abstract RequestContext requestContext();

    public boolean isMultipart() {
        String ct = header("Content-Type");
        return ct != null && ct.toLowerCase().startsWith("multipart/form-data");
    }

    public MultipartForm multipart() throws IOException {
        checkMultipartContentType();
        if (cachedMultipart == null) {
            cachedMultipart = MultipartForm.parse(header("Content-Type"), body());
        }
        return cachedMultipart;
    }

    public String param(String name) {
        String value = queryParam(name);
        if (value != null) {
            return value;
        }
        value = pathVar(name);
        if (value != null) {
            return value;
        }
        return header(name);
    }

    public <T> T param(String name, Class<T> type) {
        return coerceText(param(name), type);
    }

    public void maxBodySize(long maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    public String bodyText() throws IOException {
        return new String(body(), charsetFromContentType());
    }

    public <T> T bodyAsJson(Class<T> type) throws IOException {
        return bodyAsJson((java.lang.reflect.Type) type);
    }

    public <T> T bodyAsJson(java.lang.reflect.Type type) throws IOException {
        checkJsonContentType();
        @SuppressWarnings("unchecked")
        T value = (T) jsonCodec.fromJson(bodyText(), type);
        return value;
    }

    public abstract HttpContext status(int status);

    public abstract int statusCode();

    public abstract HttpContext headerSet(String name, String value);

    public abstract HttpContext output(byte[] data) throws IOException;

    /**
     * Switch this response to Server-Sent Events (SSE) mode.
     * Sets {@code Content-Type: text/event-stream}, sends response headers with
     * chunked transfer encoding, and returns a {@link SseEmitter} for writing events.
     */
    public abstract SseEmitter sse() throws IOException;

    public HttpContext output(String text) throws IOException {
        output(text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public HttpContext outputJson(Object value) throws IOException {
        headerSet("Content-Type", "application/json; charset=utf-8");
        output(jsonCodec.toJson(value).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public HttpContext send(int status, String text) throws IOException {
        status(status);
        if (status != 204 && status != 304) {
            headerSet("Content-Type", "text/plain; charset=utf-8");
        }
        return output(text);
    }

    public HttpContext sendJson(int status, Object value) throws IOException {
        status(status);
        return outputJson(value);
    }

    protected Coercer coercer = new DefaultCoercer();

    protected static String blankToNull(String s) {
        return s != null && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    protected <T> T coerceText(String value, Class<T> type) {
        return coercer.coerce(value, type);
    }

    private Charset charsetFromContentType() {
        String ct = header("Content-Type");
        if (ct != null) {
            Matcher m = CHARSET_PATTERN.matcher(ct);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private void checkJsonContentType() throws IOException {
        String ct = header("Content-Type");
        if (ct != null && !ct.isBlank() && !ct.toLowerCase().contains("json")) {
            throw new IOException("Expected application/json but got " + ct);
        }
    }

    private void checkMultipartContentType() throws IOException {
        String ct = header("Content-Type");
        if (ct == null || ct.isBlank() || !ct.toLowerCase().startsWith("multipart/form-data")) {
            throw new IOException("Expected multipart/form-data but got " + ct);
        }
    }
}
