package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.util.Strings;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.body.MultipartForm;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Abstract base class for HTTP request/response contexts. Subclasses bridge
 * to a specific server implementation (raw socket, JDK HttpExchange, etc.)
 * while providing a uniform API for reading requests and writing responses.
 */
public abstract class HttpContext {
    protected final JsonCodec jsonCodec;
    protected final Coercer coercer;
    protected volatile long maxBodySize = 10_485_760L;
    protected final Map<String, String> pathVariables = new LinkedHashMap<>(4);

    protected HttpContext(JsonCodec jsonCodec, Coercer coercer) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.coercer = Objects.requireNonNull(coercer, "coercer");
    }

    // == Request ==

    /** Returns the HTTP method (GET, POST, etc.). */
    public abstract String method();

    /** Returns the raw request path. */
    public abstract String path();

    /** Returns the first query parameter value for the given name, or null. */
    public abstract String queryParam(String name);

    /** Returns all query parameter values for the given name. */
    public abstract List<String> queryParams(String name);

    /** Returns an unmodifiable map of all query parameters. */
    public abstract Map<String, List<String>> queryParams();

    /**
     * Returns the value of a single query parameter coerced to the
     * given type, or null if absent.
     */
    public <T> T queryParam(String name, Class<T> type) {
        return coerceText(queryParam(name), type);
    }

    /**
     * Returns the first request header value for the given name, or null.
     * Header names are case-insensitive.
     */
    public abstract String header(String name);

    /**
     * Returns all request header values for the given name.
     * Header names are case-insensitive.
     */
    public abstract List<String> headers(String name);

    /**
     * Returns the value of a single request header coerced to the
     * given type, or null if absent.
     */
    public <T> T header(String name, Class<T> type) {
        return coerceText(header(name), type);
    }

    /** Returns an unmodifiable map of all request headers. */
    public abstract Map<String, List<String>> headers();

    /** Returns the current response header value for the given name, or null. */
    protected abstract String responseHeader(String name);

    /** Returns the request context for this request. */
    public abstract RequestContext requestContext();

    /** Returns true if the request has a multipart/form-data content type. */
    public boolean isMultipart() { return multipart() != null; }

    /**
     * Parses and returns the multipart form data, or null if the request
     * is not a multipart upload.
     */
    public MultipartForm multipart() {
        String ct = header("Content-Type");
        if (ct == null) return null;
        try {
            return MultipartForm.parse(ct, body());
        } catch (IOException e) {
            return null;
        }
    }

    /** Returns a path parameter value by name. */
    public String pathVar(String name) {
        return pathVariables.get(name);
    }

    /** Returns an unmodifiable map of all path parameter values. */
    public Map<String, String> pathVars() {
        return Collections.unmodifiableMap(pathVariables);
    }

    /** Sets all path variables from a route match. Returns this for chaining. */
    public HttpContext pathVars(Map<String, String> vars) {
        this.pathVariables.putAll(vars);
        return this;
    }

    /**
     * Returns the value of a path parameter coerced to the given type.
     */
    public <T> T pathVar(String name, Class<T> type) {
        return coerceText(pathVar(name), type);
    }

    /**
     * Returns a request parameter (from query string first, then path).
     */
    public String param(String name) {
        var value = queryParam(name);
        return value != null ? value : pathVar(name);
    }

    /**
     * Returns a request parameter coerced to the given type.
     */
    public <T> T param(String name, Class<T> type) {
        return coerceText(param(name), type);
    }

    // == Body ==

    /**
     * Sets the maximum allowed request body size in bytes.
     * Requests exceeding this limit receive a 413 Payload Too Large
     * response. Default is 10 MiB.
     *
     * @return this context for chaining
     */
    public HttpContext maxBodySize(long maxBodySize) {
        if (maxBodySize <= 0) throw new IllegalArgumentException("maxBodySize must be positive");
        this.maxBodySize = maxBodySize;
        return this;
    }

    /**
     * Reads the request body, enforcing the configured max body size.
     *
     * @throws BodyTooLargeException if the body exceeds maxBodySize
     */
    protected final byte[] readBodyLimited(InputStream input) throws IOException {
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            if (total > maxBodySize - read) throw new BodyTooLargeException(maxBodySize);
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    /** Reads the request body into a string using the charset from the Content-Type header. */
    public String bodyText() throws IOException {
        return new String(body(), charsetFromContentType());
    }

    /** Deserializes the request body as JSON into the given type. */
    public <T> T bodyAsJson(Class<T> type) throws IOException {
        return bodyAsJson((Type) type);
    }

    /** Deserializes the request body as JSON into the given type. */
    public <T> T bodyAsJson(Type type) throws IOException {
        checkJsonContentType();
        @SuppressWarnings("unchecked")
        T value = (T) jsonCodec.fromJson(bodyText(), type);
        return value;
    }

    /** Returns the raw request body bytes. */
    public abstract byte[] body() throws IOException;

    // == Response ==

    /**
     * Sets the HTTP response status code.
     *
     * @return this context for chaining
     */
    public abstract HttpContext status(int status);

    /** Returns the HTTP response status code. */
    public abstract int status();

    /**
     * Sets a response header. Overwrites any existing value for the name.
     *
     * @return this context for chaining
     */
    public abstract HttpContext headerSet(String name, String value);

    /**
     * Validates that a header value does not contain CR or LF characters,
     * preventing HTTP response header injection.
     *
     * @throws IllegalArgumentException if the value contains CR or LF
     */
    protected static void validateHeaderValue(String value) {
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n') {
                    throw new IllegalArgumentException(
                        "Header value must not contain CR or LF: " +
                        value.substring(0, Math.min(i + 10, value.length())) + "...");
                }
            }
        }
    }

    /**
     * Sends a response with the given status code and binary body.
     *
     * @return this context for chaining
     */
    public abstract HttpContext output(byte[] data) throws IOException;

    /**
     * Opens an SSE (Server-Sent Events) emitter on this response.
     * The response headers must be set before calling this method.
     */
    public abstract SseEmitter sse() throws IOException;

    /** Sets up standard SSE response headers. */
    protected void setupSseHeaders() {
        headerSet("Content-Type", "text/event-stream; charset=utf-8");
        headerSet("Cache-Control", "no-cache");
        headerSet("Connection", "keep-alive");
    }

    /**
     * Sends a response with the given status code and text body.
     * Content-Type defaults to text/plain if not already set.
     *
     * @return this context for chaining
     */
    public HttpContext output(String text) throws IOException {
        if (!allowsResponseBody()) return output(new byte[0]);
        if (blankToNull(responseHeader("Content-Type")) == null) {
            headerSet("Content-Type", "text/plain; charset=utf-8");
        }
        output(text.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Sends a JSON response for the given value.
     * Content-Type defaults to application/json if not already set.
     *
     * @return this context for chaining
     */
    public HttpContext outputJson(Object value) throws IOException {
        if (!allowsResponseBody()) return output(new byte[0]);
        if (blankToNull(responseHeader("Content-Type")) == null) {
            headerSet("Content-Type", "application/json; charset=utf-8");
        }
        output(jsonCodec.toJson(value).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Sends a response with the given status code and text body.
     * Convenience shorthand for {@code status(s).output(t)}.
     *
     * @return this context for chaining
     */
    public HttpContext send(int status, String text) throws IOException {
        status(status);
        return output(text);
    }

    /**
     * Sends a JSON response with the given status code.
     * Convenience shorthand for {@code status(s).outputJson(v)}.
     *
     * @return this context for chaining
     */
    public HttpContext sendJson(int status, Object value) throws IOException {
        status(status);
        return outputJson(value);
    }

    // == Utility ==

    /** Converts a blank string to null. */
    public static String blankToNull(String text) {
        return Strings.blankToNull(text);
    }

    /**
     * Parses a URL query string into a parameter map.
     * Values are URL-decoded. Malformed percent-encoding is left as-is.
     */
    public static Map<String, List<String>> parseQueryParams(String rawQuery) {
        LinkedHashMap<String, List<String>> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return params;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? urlDecode(pair.substring(0, eq)) : urlDecode(pair);
            String value = eq >= 0 ? urlDecode(pair.substring(eq + 1)) : "";
            params.computeIfAbsent(name, k -> new ArrayList<>(1)).add(value);
        }
        return params;
    }

    /** Creates a request context from an optional correlation ID header value. */
    public static RequestContext createRequestContext(String correlationId) {
        return RequestContext.create(correlationId);
    }

    /** Coerces a string value to the given target type. */
    protected final <T> T coerceText(String value, Class<T> type) {
        return value != null ? coercer.coerce(value, type) : null;
    }

    /** Returns true if the response status allows a body. */
    protected final boolean allowsResponseBody() {
        int status = status();
        return status != 204 && status != 205 && status != 304;
    }

    /** Returns the charset from the Content-Type header, defaulting to UTF-8. */
    private Charset charsetFromContentType() {
        String ct = header("Content-Type");
        if (ct == null) return StandardCharsets.UTF_8;
        int idx = ct.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (idx < 0) return StandardCharsets.UTF_8;
        String charset = ct.substring(idx + 8).trim();
        int semi = charset.indexOf(';');
        if (semi >= 0) charset = charset.substring(0, semi).trim();
        try { return Charset.forName(charset); } catch (Exception e) { return StandardCharsets.UTF_8; }
    }

    private void checkJsonContentType() {
        String ct = header("Content-Type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).contains("application/json")) {
            throw new IllegalStateException("Expected application/json Content-Type");
        }
    }

    private static String urlDecode(String text) {
        try { return URLDecoder.decode(text, StandardCharsets.UTF_8); }
        catch (Exception e) { return text; }
    }

    /**
     * HTTP chunked transfer encoding output stream wrapper.
     * Writes each chunk as hex-length CRLF data CRLF,
     * and sends the terminating chunk 0 CRLF CRLF on close.
     */
    static final class ChunkedOutputStream extends OutputStream {
        private final OutputStream out;
        private static final byte[] CRLF = {'\r', '\n'};
        private static final byte[] TERMINAL_CHUNK = {'0', '\r', '\n', '\r', '\n'};
        private boolean closed;

        ChunkedOutputStream(OutputStream out) {
            this.out = Objects.requireNonNull(out, "out");
        }

        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}); }

        @Override
        public void write(byte[] data, int off, int len) throws IOException {
            if (len == 0) return;
            String hex = Integer.toHexString(len);
            out.write(hex.getBytes(StandardCharsets.US_ASCII));
            out.write(CRLF);
            out.write(data, off, len);
            out.write(CRLF);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                out.write(TERMINAL_CHUNK);
                out.flush();
            } finally { out.close(); }
        }
    }
}
