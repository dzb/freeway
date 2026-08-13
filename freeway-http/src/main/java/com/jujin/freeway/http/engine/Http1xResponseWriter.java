package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.jujin.freeway.http.sse.SseEmitter;

/**
 * HTTP/1.x response writer: serializes the status line, headers, and body
 * straight onto the connection's buffered output stream. Stateless — one
 * shared instance reads everything from the context, so the hot path keeps
 * the pre-encoded byte constants and a single flush per response.
 */
final class Http1xResponseWriter implements HttpResponseWriter {

    static final Http1xResponseWriter INSTANCE = new Http1xResponseWriter();

    // Pre-encoded constants for hot-path headers
    private static final byte[] HTTP11 = "HTTP/1.1 ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] COLSP = ": ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONN_KA = "Connection: keep-alive\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONN_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CL_PREFIX = "Content-Length: ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] TE_CHUNKED = "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SPACE = " ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] TERMINAL_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private Http1xResponseWriter() {}

    @Override
    public void writeHead(HttpContextDefault ctx) throws IOException {
        writeStatusLineAndHeaders(ctx);
    }

    @Override
    public void writeBody(HttpContextDefault ctx, byte[] data) throws IOException {
        writeBody(ctx, data, 0, data.length);
    }

    @Override
    public void writeBody(HttpContextDefault ctx, byte[] data, int offset, int length)
            throws IOException {
        OutputStream rawOut = ctx.rawOut;
        boolean bodyAllowed = ctx.allowsResponseBody();
        boolean suppressBody =
            ResponseFraming.suppressBodyBytes(bodyAllowed, ctx.method());
        // HEAD response must report the same Content-Length as GET (RFC 7231 §4.3.2)
        int contentLength = bodyAllowed ? length : 0;

        // Headers are emitted once, on the first body write — a streaming
        // response calls writeBody repeatedly.
        if (!ctx.headersWritten) {
            ctx.headersWritten = true;
            if (bodyAllowed && ctx.chunkedResponse) {
                rawOut.write(TE_CHUNKED);
                if (!ctx.hasResponseHeader("Connection")) {
                    rawOut.write(ctx.isKeepAlive() ? CONN_KA : CONN_CLOSE);
                }
            } else {
                // Content-Length
                if (bodyAllowed && !ctx.hasResponseHeader("Content-Length")) {
                    rawOut.write(CL_PREFIX);
                    rawOut.write(contentLengthBytes(contentLength));
                    rawOut.write(CRLF);
                }
                // Connection
                if (!ctx.hasResponseHeader("Connection")) {
                    rawOut.write(ctx.isKeepAlive() ? CONN_KA : CONN_CLOSE);
                }
            }
            rawOut.write(CRLF); // end headers
        }

        // Body
        if (!suppressBody && length > 0) {
            if (ctx.chunkedResponse) {
                rawOut.write(Integer.toHexString(length).getBytes(
                    StandardCharsets.ISO_8859_1));
                rawOut.write(CRLF);
                rawOut.write(data, offset, length);
                rawOut.write(CRLF);
            } else {
                rawOut.write(data, offset, length);
            }
        }
    }

    @Override
    public void end(HttpContextDefault ctx) throws IOException {
        if (ctx.chunkedResponse && !ResponseFraming.suppressBodyBytes(
                ctx.allowsResponseBody(), ctx.method())) {
            // An empty chunked body never calls writeBody — emit the head
            // (Transfer-Encoding + Connection) here so the terminal chunk
            // has headers to follow.
            if (!ctx.headersWritten) {
                writeBody(ctx, new byte[0]);
            }
            ctx.rawOut.write(TERMINAL_CHUNK);
        }
        ctx.rawOut.flush();
    }

    @Override
    public SseEmitter openSse(HttpContextDefault ctx) throws IOException {
        // SSE terminates the HTTP/1.1 exchange: SseEmitter.complete() closes the
        // shared buffered output stream, so this connection must not be reused.
        // responded is still unset here (sse() marks it after openSse), so the
        // normal setHeader() contract applies.
        ctx.setKeepAlive(false);
        ctx.setHeader("Connection", "close");

        OutputStream rawOut = ctx.rawOut;
        writeStatusLineAndHeaders(ctx);
        rawOut.write("Transfer-Encoding: chunked".getBytes(StandardCharsets.ISO_8859_1));
        rawOut.write(CRLF);
        rawOut.write(CRLF);
        rawOut.flush();
        return new SseEmitter(new ChunkedOutputStream(rawOut));
    }

    /** Status line plus response headers, without the framing headers
     *  (Content-Length/Transfer-Encoding/Connection) that writeHead adds
     *  during the body write. */
    private static void writeStatusLineAndHeaders(HttpContextDefault ctx)
            throws IOException {
        OutputStream rawOut = ctx.rawOut;
        rawOut.write(HTTP11);
        rawOut.write(statusCodeBytes(ctx.status()));
        rawOut.write(SPACE);
        rawOut.write(reasonBytes(ctx.status()));
        rawOut.write(CRLF);
        for (var entry : ctx.responseHeaderEntries()) {
            rawOut.write(entry.getKey().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(COLSP);
            rawOut.write(entry.getValue().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(CRLF);
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
        for (int i = 0; i < 256; i++) {
            CL_BYTES[i] = String.valueOf(i).getBytes(StandardCharsets.ISO_8859_1);
        }
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
}
