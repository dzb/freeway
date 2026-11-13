package com.jujin.freeway.http.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.internal.ResponseFraming;
import com.jujin.freeway.http.sse.SseEmitter;

/**
 * HTTP/1.x response writer: serializes the status line, headers, and body
 * straight onto the connection's buffered output stream.
 *
 * <p>One writer per response: the framing decision ({@code Content-Length},
 * {@code Transfer-Encoding: chunked}, {@code Connection}) is computed once
 * from the immutable {@link ResponseHead}, and the {@code headersWritten}
 * flag cannot leak into the next request on a keep-alive connection.</p>
 */
final class Http1xResponseWriter implements HttpResponseWriter {

    private static final byte[] HTTP11 = "HTTP/1.1 ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] COLSP = ": ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONN_KA = "Connection: keep-alive\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] CONN_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] TE_CHUNKED = "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] SPACE = " ".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] TERMINAL_CHUNK = {'0', '\r', '\n', '\r', '\n'};

    private final OutputStream out;
    private final boolean keepAlive;
    private boolean headersWritten;
    private boolean chunked;

    Http1xResponseWriter(OutputStream out, boolean keepAlive) {
        this.out = out;
        this.keepAlive = keepAlive;
    }

    @Override
    public void writeHead(ResponseHead head) throws IOException {
        if (headersWritten) return;
        headersWritten = true;

        boolean bodyAllowed = head.allowsBody();
        chunked = bodyAllowed && !head.hasHeader("Content-Length");

        out.write(HTTP11);
        out.write(statusCodeBytes(head.status()));
        out.write(SPACE);
        out.write(reasonBytes(head.status()));
        out.write(CRLF);
        for (ResponseHead.Header header : head.headers()) {
            // Status-based body suppression owns Content-Length; the
            // transport owns transfer framing, so a handler-supplied
            // Transfer-Encoding cannot override chunked/known-length.
            if (ResponseFraming.suppressesHeader(header.name(), bodyAllowed)
                    || header.name().equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }
            out.write(header.name().getBytes(StandardCharsets.ISO_8859_1));
            out.write(COLSP);
            out.write(header.value().getBytes(StandardCharsets.ISO_8859_1));
            out.write(CRLF);
        }
        if (chunked) {
            out.write(TE_CHUNKED);
        }
        if (!head.hasHeader("Connection")) {
            out.write(keepAlive ? CONN_KA : CONN_CLOSE);
        }
        out.write(CRLF); // end headers
    }

    @Override
    public void writeBody(ResponseHead head, byte[] data, int offset, int length)
            throws IOException {
        if (!headersWritten) writeHead(head);
        if (!head.sendsBodyBytes() || length <= 0) return;
        if (chunked) {
            out.write(Integer.toHexString(length).getBytes(StandardCharsets.ISO_8859_1));
            out.write(CRLF);
            out.write(data, offset, length);
            out.write(CRLF);
        } else {
            out.write(data, offset, length);
        }
    }

    @Override
    public void end(ResponseHead head) throws IOException {
        if (!headersWritten) writeHead(head);
        if (chunked && head.sendsBodyBytes()) {
            out.write(TERMINAL_CHUNK);
        }
        out.flush();
    }

    @Override
    public SseEmitter openSse(ResponseHead head) throws IOException {
        // SSE terminates the HTTP/1.1 exchange: SseEmitter.close() closes the
        // shared buffered output stream, so the connection must not be reused.
        // The shared policy deliberately doesn't write Connection; this
        // transport forces close and owns the chunked framing.
        ResponseHead sseHead = head
            .withoutHeader("Content-Length")
            .withoutHeader("Transfer-Encoding")
            .withHeader("Connection", "close");
        writeHead(sseHead);
        out.flush();
        return new SseEmitter(new ChunkedOutputStream(out));
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
        REASON_BYTES[415] = "Unsupported Media Type".getBytes(StandardCharsets.ISO_8859_1);
        REASON_BYTES[416] = "Range Not Satisfiable".getBytes(StandardCharsets.ISO_8859_1);
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
}
