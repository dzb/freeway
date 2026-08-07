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
import java.util.Optional;
import javax.net.ssl.SSLSession;

/**
 * {@link HttpContext} implementation backed by a raw socket connection.
 * Writes HTTP/1.1 response wire format directly to the output stream.
 */
public class HttpContextDefault extends HttpContext {

    private String method, path, rawQuery;
    private Map<String, List<String>> requestHeaders, queryParams;
    private InputStream bodyStream;
    private long contentLength;
    private boolean chunked, http10, keepAlive;
    OutputStream rawOut;
    private RequestContext requestContext;
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private int responseStatus = 200;
    private boolean responded;
    /** Transport-specific response writer. Defaults to HTTP/1.1; the HTTP/2
     *  session replaces it per stream (each stream gets a fresh context). */
    private HttpResponseWriter writer = Http11ResponseWriter.INSTANCE;
    private byte[] cachedBody;
    private boolean secure;
    private SSLSession sslSession;
    // Shared drain buffer — reused across drainUnreadBody calls
    private byte[] drainBuf;

    public HttpContextDefault(JsonCodec jsonCodec, Coercer coercer) {
        super(jsonCodec, coercer);
    }

    /** Routes responses through the given transport writer (HTTP/2 stream). */
    void setWriter(HttpResponseWriter writer) {
        this.writer = writer;
    }

    /** Sets the maximum request body size. Called after reset() or construction. */
    public void setMaxBodySize(long maxBodySize) {
        this.maxBodySize = maxBodySize;
    }

    /** Marks this request as transported over TLS. */
    void setSecure(boolean secure) {
        this.secure = secure;
    }

    /** Attaches the TLS session for this request. */
    void setSslSession(SSLSession sslSession) {
        this.sslSession = sslSession;
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public SSLSession sslSession() {
        return sslSession;
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
    public Optional<String> queryParam(String name) {
        List<String> values = queryParams.get(name);
        return (values != null && !values.isEmpty())
                ? Optional.of(values.getFirst()) : Optional.empty();
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
        requestHeaders.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public Optional<String> header(String name) {
        List<String> values = requestHeaders.get(name);
        if (values != null && !values.isEmpty())
            return Optional.of(values.getFirst());
        for (var entry : requestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)
                && !entry.getValue().isEmpty()) {
                return Optional.of(entry.getValue().getFirst());
            }
        }
        return Optional.empty();
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
    public HttpContext setHeader(String name, String value) {
        if (responded) return this;
        validateHeaderName(name);
        validateHeaderValue(value);
        // Single source of truth — response writers read this in writeHead().
        responseHeaders.put(name, value);
        return this;
    }

    @Override
    public HttpContext output(byte[] data) throws IOException {
        if (responded) return this;
        responded = true;
        writer.writeHead(this);
        writer.writeBody(this, data);
        writer.end(this);
        return this;
    }

    @Override
    public SseEmitter sse() throws IOException {
        // Mark responded only after the writer opened the stream — the writer
        // still needs setHeader() while assembling the SSE head (e.g. the
        // HTTP/1.1 Connection: close override).
        setupSseHeaders();
        SseEmitter emitter = writer.openSse(this);
        responded = true;
        return emitter;
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
                // Drain through the bounded body stream so we stop exactly at
                // the end of the body. Reading the raw stream would block on
                // a keep-alive socket and swallow the next request.
                InputStream remaining = bodyStream();
                while (remaining.read(drainBuf) >= 0) { /* drain */ }
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

    static byte[] reasonBytes(int status) {
        if (status >= 0 && status < REASON_BYTES.length) {
            byte[] b = REASON_BYTES[status];
            if (b != null) return b;
        }
        return new byte[0];
    }

    // Pre-encoded status code digits indexed by status code (0-599)
    private static final byte[][] STATUS_CODE_BYTES = new byte[600][];
    static {
        for (int i = 0; i < STATUS_CODE_BYTES.length; i++) {
            STATUS_CODE_BYTES[i] = String.valueOf(i).getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    static byte[] statusCodeBytes(int status) {
        if (status >= 0 && status < STATUS_CODE_BYTES.length) {
            return STATUS_CODE_BYTES[status];
        }
        return String.valueOf(status).getBytes(StandardCharsets.ISO_8859_1);
    }

    // Pre-encoded Content-Length digits for common small body sizes (0-Ki)
    // Lazily expanded: the first request with a given length populates the slot
    private static final byte[][] CL_BYTES = new byte[4096][];
    static {
        // eagerly fill the most common lengths
        for (int i = 0; i < 256; i++) {
            CL_BYTES[i] = String.valueOf(i).getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    boolean hasResponseHeaderIgnoreCase(String name) {
        for (String key : responseHeaders.keySet()) {
            if (key.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Unmodifiable view of the response headers — consumed by response writers. */
    public Map<String, String> responseHeaders() {
        return Collections.unmodifiableMap(responseHeaders);
    }

    void setKeepAlive(boolean keepAlive) {
        this.keepAlive = keepAlive;
    }

    static byte[] contentLengthBytes(int length) {
        if (length >= 0 && length < CL_BYTES.length) {
            byte[] b = CL_BYTES[length];
            if (b != null) return b;
            b = String.valueOf(length).getBytes(StandardCharsets.ISO_8859_1);
            CL_BYTES[length] = b;
            return b;
        }
        return String.valueOf(length).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * HTTP chunked transfer encoding output stream wrapper.
     * Writes each chunk as {@code hex-length\r\n} + data + {@code \r\n},
     * and sends the terminating chunk {@code 0\r\n\r\n} on close.
     */
    static final class ChunkedOutputStream extends OutputStream {
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
