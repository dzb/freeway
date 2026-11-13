package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.net.ssl.SSLSession;

import com.jujin.freeway.http.Compression;
import com.jujin.freeway.http.HttpTransport;
import com.jujin.freeway.http.ResponseBody;
import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.ResponsePlan;
import com.jujin.freeway.http.internal.HeaderStore;
import com.jujin.freeway.http.internal.HttpUtils;

/**
 * The built-in engine's {@link HttpTransport}: one per request, backed by
 * the connection's socket (HTTP/1.x) or one HTTP/2 stream. It owns the
 * request snapshot, the body framing state, the raw output stream, the
 * per-response writer, the committed flag and the keep-alive decision; all
 * response policy (status, headers, gzip, Content-Length) lives in the {@code
 * HttpExchangeSupport} that composes it.
 */
public final class HttpTransportImpl implements HttpTransport {

    /** Bodies below this size use buffered streaming — transferTo setup cost
     *  outweighs the copy savings on small files. */
    private static final long MIN_SENDFILE_BYTES = 64 * 1024;

    @FunctionalInterface
    interface FileSender {
        void transfer(FileChannel channel, long offset, long length)
            throws IOException;
    }

    private final String method;
    private final String path;
    private final Map<String, List<String>> queryParams;
    private final Map<String, List<String>> requestHeaders;
    private final RequestBody requestBody;
    private final OutputStream rawOut;
    /** Whether the connection may serve another request after this one;
     *  SSE and {@code Connection: close} flip it off mid-request. */
    private boolean keepAlive;
    /** HTTP/1.x commit flag; HTTP/2 reads the stream's exact fact below. */
    private volatile boolean committed;
    /** For HTTP/2, the stream owns the exact HEADERS-dispatched fact. */
    private BooleanSupplier commitProbe;
    private final boolean secure;
    private final SSLSession sslSession;
    private final String remoteAddress;

    private HttpResponseWriter writer;
    private FileSender fileSender;

    /**
     * @param method        request method
     * @param path          request path (query string excluded)
     * @param rawQuery      raw query string, or null
     * @param headers       request headers; names are lower-cased in the snapshot
     * @param bodyStream    raw stream; framing comes from {@code contentLength}/{@code chunked}
     * @param contentLength declared body length, or -1 when chunked/unknown
     * @param chunked       true for {@code Transfer-Encoding: chunked}
     * @param rawOut        the response byte sink for this request
     * @param keepAlive     whether the peer allowed keep-alive for this request
     * @param secure        true when the request arrived over TLS
     * @param sslSession    the negotiated TLS session, or null
     * @param remoteAddress client address, or an empty string when unavailable
     */
    public HttpTransportImpl(String method, String path, String rawQuery,
            Map<String, List<String>> headers,
            InputStream bodyStream, long contentLength, boolean chunked,
            OutputStream rawOut, boolean keepAlive,
            boolean secure, SSLSession sslSession, String remoteAddress) {
        this.method = method;
        this.path = path;
        // Deep-copy: the query lists must be immutable too, not just the map.
        var params = new java.util.LinkedHashMap<String, List<String>>();
        HttpUtils.parseQueryParams(rawQuery)
            .forEach((name, values) -> params.put(name, List.copyOf(values)));
        this.queryParams = Map.copyOf(params);
        this.requestHeaders = HeaderStore.copyOf(headers).asMap();
        this.requestBody = new RequestBody(bodyStream, contentLength, chunked);
        this.rawOut = rawOut;
        this.keepAlive = keepAlive;
        this.secure = secure;
        this.sslSession = sslSession;
        this.remoteAddress = remoteAddress != null ? remoteAddress : "";
    }

    /** Routes responses through the given transport writer (HTTP/2 stream). */
    void setWriter(HttpResponseWriter writer) {
        this.writer = writer;
    }

    /** Enables the OS sendfile path for {@link #writeResponseFile}. */
    void setFileSender(FileSender fileSender) {
        this.fileSender = fileSender;
    }

    private HttpResponseWriter writer() {
        if (writer == null) {
            writer = new Http1xResponseWriter(rawOut, keepAlive);
        }
        return writer;
    }

    // -- request side --

    @Override
    public String method() {
        return method;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return queryParams;
    }

    @Override
    public Map<String, List<String>> headers() {
        return requestHeaders;
    }

    @Override
    public InputStream framedBodyStream() {
        return requestBody.stream();
    }

    @Override
    public boolean isSecure() {
        return secure;
    }

    @Override
    public SSLSession sslSession() {
        return sslSession;
    }

    @Override
    public String remoteAddress() {
        return remoteAddress;
    }

    @Override
    public boolean isCommitted() {
        BooleanSupplier probe = commitProbe;
        return probe != null ? probe.getAsBoolean() : committed;
    }

    /** Makes the transport read a stream-owned commit fact instead of its local flag. */
    void setCommitProbe(BooleanSupplier probe) {
        this.commitProbe = probe;
    }

    // -- response side --

    @Override
    public void writeResponse(ResponsePlan plan) throws IOException {
        ResponseHead head = plan.head();
        if (!head.sendsBodyBytes()) {
            try {
                writeHeadOnly(head);
            } finally {
                closeBody(plan.body());
            }
            return;
        }
        switch (plan.body()) {
            case ResponseBody.Empty ignored -> writeHeadOnly(head);
            case ResponseBody.Bytes bytes -> writeBytes(head, bytes.data(), plan.gzip());
            case ResponseBody.Stream stream ->
                writeStream(head, stream.stream(), stream.length(), plan.gzip());
            case ResponseBody.File file -> writeFile(head, file, plan.gzip());
            case ResponseBody.Channel channel ->
                writeChannel(head, channel.channel(), channel.offset(),
                    channel.length(), plan.gzip());
        }
    }

    private static void closeBody(ResponseBody body) throws IOException {
        if (body instanceof ResponseBody.Channel channel) {
            channel.channel().close();
        }
    }

    private void writeHeadOnly(ResponseHead head) throws IOException {
        HttpResponseWriter w = writer();
        committed = true;
        w.writeHead(head);
        w.end(head);
    }

    private void writeBytes(ResponseHead head, byte[] body, boolean gzip)
            throws IOException {
        byte[] data = gzip ? Compression.gzip(body) : body;
        ResponseHead framed = head.withHeader(
            "Content-Length", Integer.toString(data.length));
        HttpResponseWriter w = writer();
        committed = true;
        w.writeHead(framed);
        w.writeBody(framed, data, 0, data.length);
        w.end(framed);
    }

    private void writeStream(ResponseHead head, InputStream body, long length,
            boolean gzip) throws IOException {
        // Content-Length is the support object's declaration. A body shape
        // that could only learn its length here (a file range) frames the head
        // in writeChannel before entering this method; a protocol writer is
        // not allowed to make a second, later decision.
        HttpResponseWriter w = writer();
        committed = true;
        w.writeHead(head);
        byte[] buffer = new byte[8192];
        if (gzip) {
            var gzipOut = Compression.gzipStream(new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b});
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (len > 0) w.writeBody(head, b, off, len);
                }
            });
            while (true) {
                int n = body.read(buffer);
                if (n < 0) break;
                if (n > 0) gzipOut.write(buffer, 0, n);
            }
            gzipOut.finish();
        } else {
            long expected = declaredLength(head);
            long remaining = expected;
            long actual = 0;
            while (true) {
                int requested = remaining >= 0
                    ? (int) Math.min(buffer.length, remaining) : buffer.length;
                if (remaining == 0) {
                    if (body.read() >= 0) {
                        failLengthMismatch(head,
                            "Response body exceeds Content-Length");
                    }
                    break;
                }
                int n = body.read(buffer, 0, requested);
                if (n < 0) break;
                if (n > 0) {
                    actual += n;
                    if (remaining >= 0) remaining -= n;
                    w.writeBody(head, buffer, 0, n);
                }
            }
            if (expected >= 0 && actual != expected) {
                failLengthMismatch(head,
                    "Response body shorter than Content-Length");
            }
        }
        w.end(head);
    }

    private void writeFile(ResponseHead head, ResponseBody.File file, boolean gzip)
            throws IOException {
        try (FileChannel channel = FileChannel.open(file.path(),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            writeChannel(head, channel, file.offset(), file.length(), gzip);
        }
    }

    private void writeChannel(ResponseHead head, FileChannel channel,
            long offset, long length, boolean gzip) throws IOException {
        try {
            long effectiveLength = length;
            if (effectiveLength < 0) {
                effectiveLength = Math.max(0, channel.size() - offset);
            }
            if (!gzip) {
                // A file range's final length is a transport fact: a negative
                // requested length means "to end", which only the open channel
                // can answer. Resolve it once, before the head is committed,
                // and use that same head for sendfile and buffered streaming.
                head = head.hasHeader("Content-Length")
                    ? head
                    : head.withHeader("Content-Length", Long.toString(effectiveLength));
            }
            if (!gzip && fileSender != null
                    && effectiveLength >= MIN_SENDFILE_BYTES) {
                writeSendfile(head, channel, offset, effectiveLength);
                return;
            }
            channel.position(offset);
            try (InputStream body = new FixedLengthInputStream(
                    Channels.newInputStream(channel), effectiveLength)) {
                writeStream(head, body, effectiveLength, gzip);
            }
        } finally {
            channel.close();
        }
    }

    private void writeSendfile(ResponseHead head, FileChannel channel,
            long offset, long length) throws IOException {
        HttpResponseWriter w = writer();
        committed = true;
        w.writeHead(head);
        rawOut.flush();
        fileSender.transfer(channel, offset, length);
        w.end(head);
    }

    @Override
    public com.jujin.freeway.http.sse.SseEmitter openSse(ResponseHead head)
            throws IOException {
        // SSE terminates an HTTP/1.1 exchange; the H2 stream session ignores
        // the flag, so this is correct for both built-in writers.
        keepAlive = false;
        HttpResponseWriter w = writer();
        committed = true;
        return w.openSse(head);
    }

    // -- package-private helpers for Session --

    /** Drains any unread request body so the connection can be reused. */
    boolean drainRequestBody(long limit) {
        return requestBody.drain(limit);
    }

    /**
     * Applies the handler-set {@code Connection} response header to the
     * keep-alive decision so a {@code Connection: close` response really
     * closes the connection after the current request.
     */
    void syncKeepAliveFromResponse(String connection) {
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

    boolean isKeepAlive() {
        return keepAlive;
    }

    private static long declaredLength(ResponseHead head) {
        String value = head.header("Content-Length");
        if (value == null) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void failLengthMismatch(ResponseHead head, String message)
            throws IOException {
        keepAlive = false;
        writer().onLengthMismatch(head);
        throw new IOException(message);
    }
}
