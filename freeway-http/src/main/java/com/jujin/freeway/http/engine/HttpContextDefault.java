package com.jujin.freeway.http.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLSession;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.http.AbstractHttpContext;
import com.jujin.freeway.http.HttpResponse;
import com.jujin.freeway.http.HttpUtils;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.http.sse.SseEmitter;

/**
 * {@link HttpContext} implementation backed by a raw socket connection.
 * Response framing is delegated to a transport {@link HttpResponseWriter}
 * (HTTP/1.1 wire format or HTTP/2 stream frames); this class owns the shared
 * request/response state, gzip compression, and streaming helpers.
 */
public class HttpContextDefault extends AbstractHttpContext {

    private String method, path, rawQuery;
    private String remoteAddress = "";
    private Map<String, List<String>> requestHeaders, queryParams;
    private boolean http10, keepAlive;
    OutputStream rawOut;
    private final CaseInsensitiveHeaders responseHeaders = new CaseInsensitiveHeaders();
    private RequestBody requestBody;
    private int responseStatus = 200;
    private boolean responded;
    /** Transport-specific response writer. Defaults to HTTP/1.1; the HTTP/2
     *  session replaces it per stream (each stream gets a fresh context). */
    private HttpResponseWriter writer = Http11ResponseWriter.INSTANCE;
    private boolean secure;
    private SSLSession sslSession;
    /** Set once the HTTP/1.1 writer has emitted status/headers — streaming
     *  responses must not repeat them per chunk. */
    boolean headersWritten;
    /** HTTP/1.1 responses with unknown length (or compressed streaming) are
     *  framed with Transfer-Encoding: chunked instead of Content-Length. */
    boolean chunkedResponse;
    private HttpServerConfig.CompressionConfig compression =
        HttpServerConfig.CompressionConfig.DEFAULT;
    private FileSender fileSender;
    /** Bodies below this size use buffered streaming — transferTo setup cost
     *  outweighs the copy savings on small files. */
    private static final long MIN_SENDFILE_BYTES = 64 * 1024;

    @FunctionalInterface
    interface FileSender {
        void transfer(FileChannel channel, long offset, long length)
            throws IOException;
    }
    public HttpContextDefault(JsonCodec jsonCodec, Coercer coercer) {
        super(jsonCodec, coercer);
    }

    /** Creates a context seeded with the given correlation id (auto-generated
     *  when blank); keep-alive reuse updates it per request via reset(). */
    public HttpContextDefault(JsonCodec jsonCodec, Coercer coercer,
                              String correlationId) {
        super(jsonCodec, coercer, correlationId);
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

    /** Attaches the client IP address of the connection. */
    void setRemoteAddress(String remoteAddress) {
        this.remoteAddress = remoteAddress != null ? remoteAddress : "";
    }

    @Override
    public String remoteAddress() {
        return remoteAddress;
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
               OutputStream rawOut, String correlationId,
               boolean http10, boolean keepAlive) {
        this.method = method;
        this.path = path;
        this.rawQuery = rawQuery;
        this.requestHeaders = requestHeaders;
        this.requestBody = new RequestBody(
            bodyStream, contentLength, chunked, () -> maxBodySize);
        this.rawOut = rawOut;
        // Clear principal/attributes and roll a fresh correlation id so
        // request N+1 on a keep-alive connection never sees request N's
        // authentication context; the incoming X-Request-Id (if any) is then
        // applied on top.
        resetExchangeMeta();
        setCorrelationId(correlationId);
        this.http10 = http10;
        this.keepAlive = keepAlive;
        this.queryParams = HttpUtils.parseQueryParams(rawQuery);
        this.responseStatus = 200;
        this.responded = false;
        this.responseHeaders.clear();
        this.pathVariables.clear();
        this.headersWritten = false;
        this.chunkedResponse = false;
    }

    /** Sets the gzip compression policy for this connection's requests. */
    void setCompression(HttpServerConfig.CompressionConfig compression) {
        if (compression != null) {
            this.compression = compression;
        }
    }

    /** Enables the OS sendfile path for {@link #outputFile}. */
    void setFileSender(FileSender fileSender) {
        this.fileSender = fileSender;
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
        return requestBody.readAll();
    }

    // --- response side ---

    @Override
    public HttpResponse status(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException(
                "Invalid HTTP status code: " + status
                    + " (must be between 100 and 599)");
        }
        this.responseStatus = status;
        return this;
    }

    @Override
    public int status() { return responseStatus; }

    @Override
    public HttpResponse setHeader(String name, String value) {
        if (responded) return this;
        validateHeaderName(name);
        validateHeaderValue(value);
        // Single source of truth — response writers read this in writeHead().
        responseHeaders.set(name, value);
        return this;
    }

    @Override
    public HttpResponse output(byte[] data) throws IOException {
        if (responded) return this;
        responded = true;
        byte[] body = data;
        if (ResponseFraming.shouldGzip(compression, responseStatus,
                allowsResponseBody(), body.length,
                acceptsGzip(), compressibleContentType())) {
            body = gzip(body);
            responseHeaders.set("Content-Encoding", "gzip");
            addVaryAcceptEncoding();
            responseHeaders.setValueIfPresent(
                "Content-Length", Integer.toString(body.length));
        }
        writer.writeHead(this);
        writer.writeBody(this, body);
        writer.end(this);
        return this;
    }

    @Override
    public HttpResponse output(InputStream in, long contentLength) throws IOException {
        if (responded) return this;
        boolean gzip = ResponseFraming.shouldGzipStream(
            compression, responseStatus, allowsResponseBody(),
            acceptsGzip(), compressibleContentType());
        if (gzip) {
            // Compressed length is unknown until the stream is consumed —
            // HTTP/1.1 falls back to chunked framing; the Content-Length
            // header (if any) must not advertise the uncompressed size.
            responseHeaders.remove("Content-Length");
            chunkedResponse = true;
            responseHeaders.set("Content-Encoding", "gzip");
            addVaryAcceptEncoding();
        } else if (!hasResponseHeader("Content-Length")) {
            if (contentLength >= 0) {
                responseHeaders.set("Content-Length", Long.toString(contentLength));
            } else {
                chunkedResponse = true;
            }
        }
        responded = true;
        writer.writeHead(this);
        byte[] buffer = new byte[8192];
        if (gzip) {
            var gzipOut = new GZIPOutputStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b});
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (len > 0) {
                        writer.writeBody(HttpContextDefault.this, b, off, len);
                    }
                }
            }, 8192);
            while (true) {
                int n = in.read(buffer);
                if (n < 0) break;
                if (n > 0) gzipOut.write(buffer, 0, n);
            }
            gzipOut.finish();
        } else {
            long remaining = contentLength;
            long actual = 0;
            while (true) {
                int requested = remaining >= 0 ? (int) Math.min(buffer.length, remaining) : buffer.length;
                if (remaining == 0) {
                    if (in.read() >= 0) {
                        keepAlive = false;
                        writer.onLengthMismatch(this);
                        throw new IOException("Response body exceeds Content-Length");
                    }
                    break;
                }
                int n = in.read(buffer, 0, requested);
                if (n < 0) break;
                if (n > 0) {
                    actual += n;
                    if (remaining >= 0) remaining -= n;
                    writer.writeBody(this, buffer, 0, n);
                }
            }
            if (contentLength >= 0 && actual != contentLength) {
                keepAlive = false;
                writer.onLengthMismatch(this);
                throw new IOException("Response body shorter than Content-Length");
            }
        }
        writer.end(this);
        return this;
    }

    @Override
    public HttpResponse outputFile(Path file, long offset, long length)
            throws IOException {
        if (responded) return this;
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return outputFile(channel, offset, length);
        }
    }

    @Override
    public HttpResponse outputFile(FileChannel channel, long offset, long length)
            throws IOException {
        if (responded) {
            channel.close();
            return this;
        }
        boolean gzip = ResponseFraming.shouldGzipFile(
            compression, responseStatus, allowsResponseBody(),
            acceptsGzip(), compressibleContentType());
        if (gzip || fileSender == null || length < MIN_SENDFILE_BYTES) {
            channel.position(offset);
            try {
                return output(new FixedLengthInputStream(
                    Channels.newInputStream(channel), length), length);
            } finally {
                channel.close();
            }
        }
        responseHeaders.set("Content-Length", Long.toString(length));
        responded = true;
        writer.writeHead(this);
        writer.writeBody(this, new byte[0]); // status + headers, once
        rawOut.flush();
        // HEAD (and bodyless statuses) must not put file bytes on the wire:
        // the buffered path suppresses them inside the writers, but this
        // sendfile path transfers straight to the socket — skip it and keep
        // the same Content-Length as GET (RFC 9110 §9.3.2).
        if (ResponseFraming.suppressBodyBytes(allowsResponseBody(), method)) {
            channel.close();
        } else {
            try {
                fileSender.transfer(channel, offset, length);
            } finally {
                channel.close();
            }
        }
        writer.end(this);
        return this;
    }

    private boolean acceptsGzip() {
        for (var entry : requestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("accept-encoding")) {
                for (String value : entry.getValue()) {
                    for (String part : value.split(",")) {
                        String token = part.trim();
                        int q = token.indexOf(';');
                        String name = q < 0 ? token : token.substring(0, q).trim();
                        if ("gzip".equalsIgnoreCase(name)) {
                            if (q < 0) return true;
                            String params = token.substring(q + 1).toLowerCase(Locale.ROOT);
                            return !qValueIsZero(params);
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean qValueIsZero(String params) {
        for (String part : params.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && "q".equals(kv[0].trim())) {
                try {
                    return Double.parseDouble(kv[1].trim()) == 0.0;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean compressibleContentType() {
        String contentType = responseHeaders.get("Content-Type");
        if (contentType == null) return false;
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/")
            || lower.startsWith("application/json")
            || lower.startsWith("application/javascript")
            || lower.startsWith("application/xml")
            || lower.startsWith("application/xhtml+xml")
            || lower.startsWith("image/svg+xml");
    }

    /** Vary merge for the compression path: writes directly into the
     *  internal header store because the response is already committed
     *  ({@code setHeader} is a no-op after {@code responded}). */
    private void addVaryAcceptEncoding() {
        responseHeaders.set("Vary",
            HttpUtils.mergeVary(responseHeaders.get("Vary"), "Accept-Encoding"));
    }

    private static byte[] gzip(byte[] data) throws IOException {
        var bos = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        try (var gzip = new GZIPOutputStream(bos)) {
            gzip.write(data);
        }
        return bos.toByteArray();
    }

    @Override
    public SseEmitter sse() throws IOException {
        if (responded) throw new IllegalStateException("Response already committed");
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

    @Override
    public boolean isResponded() { return responded; }

    /**
     * Drains any unread request body bytes so the connection
     * can be reused for the next request (keep-alive).
     *
     * @return true if the request body was fully consumed
     */
    boolean drainUnreadBody() {
        return requestBody.drain();
    }

    /**
     * Applies a handler-set {@code Connection} response header to the
     * keep-alive decision so a {@code Connection: close} response really
     * closes the connection after the current request.
     */
    void syncKeepAliveFromResponse() {
        String connection = responseHeaders.get("connection");
        if (connection == null) return;
        for (String token : connection.split(",")) {
            String t = token.trim();
            if ("close".equalsIgnoreCase(t)) {
                keepAlive = false;
            } else if ("keep-alive".equalsIgnoreCase(t)) {
                keepAlive = true;
            }
        }
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

    boolean hasResponseHeader(String name) {
        return responseHeaders.contains(name);
    }

    /** Response headers in insertion order — consumed by response writers. */
    public List<Map.Entry<String, String>> responseHeaderEntries() {
        return responseHeaders.entries();
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
