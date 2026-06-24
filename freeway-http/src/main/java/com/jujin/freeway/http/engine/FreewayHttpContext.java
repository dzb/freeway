package com.jujin.freeway.http.engine;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.RequestContext;
import com.jujin.freeway.http.sse.SseEmitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link HttpContext} implementation backed by a raw socket connection.
 * Writes HTTP/1.1 response wire format directly to the output stream.
 */
public final class FreewayHttpContext extends HttpContext {

    private String method, path, rawQuery;
    private Map<String, List<String>> requestHeaders, queryParams;
    private InputStream bodyStream;
    private long contentLength;
    private boolean chunked, http10, keepAlive;
    private OutputStream rawOut;
    private RequestContext requestContext;
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private int responseStatus = 200;
    private boolean responded;
    private byte[] cachedBody;
    // Shared drain buffer — reused across drainUnreadBody calls
    private byte[] drainBuf;

    public FreewayHttpContext(JsonCodec jsonCodec, Coercer coercer) {
        super(jsonCodec, coercer);
    }

    /** Reuse this context for a new request. */
    void reset(String method, String path, String rawQuery,
               Map<String, List<String>> requestHeaders,
               InputStream bodyStream, long contentLength, boolean chunked,
               OutputStream rawOut, RequestContext requestContext,
               boolean http10, boolean keepAlive) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.requestHeaders = requestHeaders;
        this.bodyStream = bodyStream;
        this.contentLength = contentLength;
        this.chunked = chunked;
        this.rawOut = rawOut;
        this.requestContext = requestContext;
        this.http10 = http10;
        this.keepAlive = keepAlive;
        this.queryParams = parseQueryParams(rawQuery);
        this.responseStatus = 200;
        this.responded = false;
        this.responseHeaders.clear();
        this.pathVariables.clear();
        this.cachedBody = null;
    }

    // -- request side ---

    @Override
    public String method() { return method; }

    @Override
    public String path() { return path; }

    @Override
    public String queryParam(String name) {
        List<String> values = queryParams.get(name);
        return values != null && !values.isEmpty() ? values.getFirst() : null;
    }

    @Override
    public List<String> queryParams(String name) {
        List<String> vals = queryParams.get(name);
        return vals != null ? List.copyOf(vals) : List.of();
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return Collections.unmodifiableMap(queryParams);
    }

    @Override
    public Map<String, List<String>> headers() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        requestHeaders.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String header(String name) {
        List<String> values = requestHeaders.get(name);
        if (values != null && !values.isEmpty()) return values.getFirst();
        // fallback: case-insensitive lookup
        for (var entry : requestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                && !entry.getValue().isEmpty()) {
                return entry.getValue().getFirst();
            }
        }
        return null;
    }

    @Override
    protected String responseHeader(String name) {
        return responseHeaders.get(name);
    }

    @Override
    public List<String> headers(String name) {
        List<String> values = requestHeaders.get(name);
        if (values != null) return List.copyOf(values);
        for (var entry : requestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return List.copyOf(entry.getValue());
            }
        }
        return List.of();
    }

    @Override
    public byte[] body() throws IOException {
        if (cachedBody == null) {
            cachedBody = readBodyLimited(bodyStream());
        }
        return cachedBody;
    }

    @Override
    public RequestContext requestContext() { return requestContext; }

    // --- response side ---

    @Override
    public HttpContext status(int status) {
        this.responseStatus = status;
        return this;
    }

    @Override
    public int status() { return responseStatus; }

    @Override
    public HttpContext headerSet(String name, String value) {
        if (responded) return this;
        validateHeaderValue(value);
        responseHeaders.put(name, value);
        return this;
    }

    // Pre-encoded constants for hot-path headers
    private static final byte[] HTTP11 = "HTTP/1.1 ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] COLSP = ": ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONN_KA = "Connection: keep-alive\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONN_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CL_PREFIX = "Content-Length: ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SPACE = " ".getBytes(StandardCharsets.ISO_8859_1);

    @Override
    public HttpContext output(byte[] data) throws IOException {
        if (responded) return this;
        responded = true;

        boolean headRequest = "HEAD".equalsIgnoreCase(method);
        boolean bodyAllowed = allowsResponseBody();
        int length = bodyAllowed && !headRequest ? data.length : 0;

        // Status line: "HTTP/1.1 {code} {reason}\r\n"
        rawOut.write(HTTP11);
        rawOut.write(String.valueOf(responseStatus).getBytes(StandardCharsets.ISO_8859_1));
        rawOut.write(SPACE);
        rawOut.write(reasonBytes(responseStatus));
        rawOut.write(CRLF);

        // Response headers
        for (var entry : responseHeaders.entrySet()) {
            rawOut.write(entry.getKey().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(COLSP);
            rawOut.write(entry.getValue().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(CRLF);
        }

        // Content-Length
        if (bodyAllowed && !responseHeaders.containsKey("Content-Length")) {
            rawOut.write(CL_PREFIX);
            rawOut.write(String.valueOf(length).getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(CRLF);
        }
        // Connection
        if (!responseHeaders.containsKey("Connection")) {
            rawOut.write(keepAlive ? CONN_KA : CONN_CLOSE);
        }

        rawOut.write(CRLF); // end headers

        // Body
        if (bodyAllowed && !headRequest && data.length > 0) {
            rawOut.write(data);
        }

        rawOut.flush();
        return this;
    }

    @Override
    public SseEmitter sse() throws IOException {
        setupSseHeaders();
        // Write SSE headers directly (chunked transfer)
        writeLine("HTTP/1.1 200 OK");
        for (var entry : responseHeaders.entrySet()) {
            writeLine(entry.getKey() + ": " + entry.getValue());
        }
        writeLine("Transfer-encoding: chunked");
        if (!responseHeaders.containsKey("Connection")) {
            writeLine("Connection: " + (keepAlive ? "keep-alive" : "close"));
        }
        writeLine("");
        rawOut.flush();
        responded = true;
        // Wrap the output stream with HTTP chunked transfer encoding
        return new SseEmitter(new ChunkedOutputStream(rawOut));
    }

    // --- package-private helpers for Session ---

    boolean isKeepAlive() { return keepAlive; }

    boolean isHttp10() { return http10; }

    boolean isResponded() { return responded; }

    /**
     * Drains any unread request body bytes so the connection
     * can be reused for the next request (keep-alive).
     */
    void drainUnreadBody() {
        // Only drain when there's an actual request body to consume.
        // For GET/HEAD/DELETE (no Content-Length, not chunked), the "body stream"
        // is the raw socket InputStream — draining it would eat the next keep-alive request.
        if (cachedBody != null) return;
        if (contentLength <= 0 && !chunked) return;
        try {
            if (bodyStream != null) {
                if (drainBuf == null) drainBuf = new byte[2048];
                while (bodyStream.read(drainBuf) >= 0) { /* drain */ }
            }
        } catch (IOException ignored) { /* best-effort */ }
    }

    // --- internal ---

    private InputStream bodyStream() throws IOException {
        if (chunked) {
            return new ChunkedInputStream(bodyStream);
        }
        if (contentLength >= 0) {
            return new FixedLengthInputStream(bodyStream, contentLength);
        }
        // no Content-Length and not chunked → read to EOF
        return bodyStream;
    }

    private void writeLine(String line) throws IOException {
        rawOut.write(line.getBytes(StandardCharsets.ISO_8859_1));
        rawOut.write('\r');
        rawOut.write('\n');
    }

    // Pre-encoded reason phrases indexed by status code
    private static final byte[][] REASON_BYTES = new byte[600][];
    static {
        REASON_BYTES[200] = "OK".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[201] = "Created".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[204] = "No Content".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[301] = "Moved Permanently".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[302] = "Found".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[304] = "Not Modified".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[400] = "Bad Request".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[401] = "Unauthorized".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[403] = "Forbidden".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[404] = "Not Found".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[405] = "Method Not Allowed".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[413] = "Payload Too Large".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[422] = "Unprocessable Content".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[426] = "Upgrade Required".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[429] = "Too Many Requests".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[500] = "Internal Server Error".getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] reasonBytes(int status) {
        if (status >= 0 && status < REASON_BYTES.length) {
            byte[] b = REASON_BYTES[status];
            if (b != null) return b;
        }
        return new byte[0];
    }

    /**
     * HTTP chunked transfer encoding output stream wrapper.
     * Writes each chunk as {@code hex-length\r\n} + data + {@code \r\n},
     * and sends the terminating chunk {@code 0\r\n\r\n} on close.
     */
    private static final class ChunkedOutputStream extends OutputStream {
        private final OutputStream out;
        private static final byte[] CRLF = {'\r', '\n'};
        private static final byte[] TERMINAL_CHUNK = {'0', '\r', '\n', '\r', '\n'};
        private boolean closed;

        ChunkedOutputStream(OutputStream out) {
            this.out = Objects.requireNonNull(out, "out");
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b});
        }

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
            } finally {
                out.close();
            }
        }
    }
}
