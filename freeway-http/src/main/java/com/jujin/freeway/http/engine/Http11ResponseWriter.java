package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.sse.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * HTTP/1.1 response writer: serializes the status line, headers, and body
 * straight onto the connection's buffered output stream. Stateless — one
 * shared instance reads everything from the context, so the hot path keeps
 * the pre-encoded byte constants and a single flush per response.
 */
final class Http11ResponseWriter implements HttpResponseWriter {

    static final Http11ResponseWriter INSTANCE = new Http11ResponseWriter();

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

    private Http11ResponseWriter() {}

    @Override
    public void writeHead(HttpContextDefault ctx) throws IOException {
        OutputStream rawOut = ctx.rawOut;
        int status = ctx.status();

        // Status line: "HTTP/1.1 {code} {reason}\r\n"
        rawOut.write(HTTP11);
        rawOut.write(HttpContextDefault.statusCodeBytes(status));
        rawOut.write(SPACE);
        rawOut.write(HttpContextDefault.reasonBytes(status));
        rawOut.write(CRLF);

        // Response headers
        for (var entry : ctx.responseHeaders().entrySet()) {
            rawOut.write(entry.getKey().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(COLSP);
            rawOut.write(entry.getValue().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(CRLF);
        }
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
        boolean headRequest = "HEAD".equalsIgnoreCase(ctx.method());
        // HEAD response must report the same Content-Length as GET (RFC 7231 §4.3.2)
        int contentLength = bodyAllowed ? length : 0;

        // Headers are emitted once, on the first body write — a streaming
        // response calls writeBody repeatedly.
        if (!ctx.headersWritten) {
            ctx.headersWritten = true;
            if (bodyAllowed && ctx.chunkedResponse) {
                rawOut.write(TE_CHUNKED);
                if (!ctx.hasResponseHeaderIgnoreCase("Connection")) {
                    rawOut.write(ctx.isKeepAlive() ? CONN_KA : CONN_CLOSE);
                }
            } else {
                // Content-Length
                if (bodyAllowed && !ctx.hasResponseHeaderIgnoreCase("Content-Length")) {
                    rawOut.write(CL_PREFIX);
                    rawOut.write(HttpContextDefault.contentLengthBytes(contentLength));
                    rawOut.write(CRLF);
                }
                // Connection
                if (!ctx.hasResponseHeaderIgnoreCase("Connection")) {
                    rawOut.write(ctx.isKeepAlive() ? CONN_KA : CONN_CLOSE);
                }
            }
            rawOut.write(CRLF); // end headers
        }

        // Body
        if (bodyAllowed && !headRequest && length > 0) {
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
        if (ctx.chunkedResponse && ctx.allowsResponseBody()
                && !"HEAD".equalsIgnoreCase(ctx.method())) {
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
        rawOut.write(HTTP11);
        rawOut.write(HttpContextDefault.statusCodeBytes(200));
        rawOut.write(SPACE);
        rawOut.write(HttpContextDefault.reasonBytes(200));
        rawOut.write(CRLF);
        for (var entry : ctx.responseHeaders().entrySet()) {
            rawOut.write(entry.getKey().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(COLSP);
            rawOut.write(entry.getValue().getBytes(StandardCharsets.ISO_8859_1));
            rawOut.write(CRLF);
        }
        rawOut.write("Transfer-Encoding: chunked".getBytes(StandardCharsets.ISO_8859_1));
        rawOut.write(CRLF);
        rawOut.write(CRLF);
        rawOut.flush();
        return new SseEmitter(new HttpContextDefault.ChunkedOutputStream(rawOut));
    }
}
