package com.jujin.freeway.http;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLSession;

import com.jujin.freeway.http.sse.SseEmitter;

/**
 * Transport SPI: the request bytes an engine reads and the response bytes it
 * writes. An engine implements this interface and composes {@link
 * HttpContextSupport}; it never subclasses a framework type.
 *
 * <p><b>Request side.</b> All returned collections are immutable snapshots.
 * {@link #bodyStream()} returns the transport-framed stream (chunked or
 * fixed-length already removed); the framework's {@code
 * HttpContextSupport} wraps it in the shared body-size limiter, so every
 * engine enforces {@code maxBodySize} with the same code.</p>
 *
 * <p><b>Response side.</b> {@link HttpContextSupport} owns every shared
 * response decision (status/header validation, {@code Date}, {@code
 * Content-Length}, gzip decision, {@code Vary}) and hands the transport an
 * immutable {@link ResponseHead}. The transport only adds protocol framing
 * ({@code Connection}, {@code Transfer-Encoding}) and moves the bytes. The
 * default stream/file methods buffer and materialize the body; the built-in
 * transport overrides them to stream and use sendfile.</p>
 */
public interface HttpTransport {

    /** Returns the request method. */
    String method();

    /** Returns the request path. */
    String path();

    /** Immutable query-parameter snapshot. */
    Map<String, List<String>> queryParams();

    /** Immutable request-header snapshot with lowercase names. */
    Map<String, List<String>> headers();

    /** Returns the transport-framed request body stream. */
    InputStream bodyStream() throws IOException;

    /** True when the request arrived over TLS. */
    boolean isSecure();

    /** Negotiated TLS session, or null for plaintext. */
    SSLSession sslSession();

    /** Client address, or an empty string when unavailable. */
    String remoteAddress();

    /**
     * Writes a fully materialized response.
     *
     * <p>When {@link ResponseHead#sendsBodyBytes()} is false (HEAD, or a
     * 204/205/304 status) the transport writes the head only and never the
     * body bytes; a HEAD keeps the {@code Content-Length} the support object
     * declared for it.</p>
     */
    void writeResponse(ResponseHead head, byte[] body) throws IOException;

    /**
     * Writes a streaming response. When {@code gzip} is true the transport
     * compresses as it writes; the head already carries {@code
     * Content-Encoding}/{@code Vary} and has no {@code Content-Length}.
     *
     * <p>The default buffers, materializes the gzip length when needed, and
     * delegates to {@link #writeResponse(ResponseHead, byte[])}. A transport
     * that can stream overrides this method. Neither the default nor an
     * override reads {@code body} when the head sends no body bytes.</p>
     */
    default void writeResponse(ResponseHead head, InputStream body, boolean gzip)
            throws IOException {
        if (!head.sendsBodyBytes()) {
            // HEAD / 204/205/304: no body bytes go on the wire, so the
            // source is never read — and no Content-Length is invented here,
            // because the head already carries whatever the support object
            // declared (or deliberately omitted).
            writeResponse(head, new byte[0]);
            return;
        }
        if (gzip) {
            byte[] compressed = Compression.gzip(body.readAllBytes());
            writeResponse(head.withHeader("Content-Length",
                Integer.toString(compressed.length)), compressed);
            return;
        }
        byte[] data = body.readAllBytes();
        String declared = head.header("Content-Length");
        if (declared != null && Long.parseLong(declared) != data.length) {
            throw new IOException("Response body length does not match Content-Length");
        }
        writeResponse(declared != null ? head
            : head.withHeader("Content-Length", Integer.toString(data.length)), data);
    }

    /**
     * Writes a file range. The default buffers the range and delegates to the
     * stream path; a transport with a zero-copy path overrides it. The file
     * is not opened at all when the head sends no body bytes.
     */
    default void writeResponseFile(ResponseHead head, Path file,
            long offset, long length, boolean gzip) throws IOException {
        if (!head.sendsBodyBytes()) {
            writeResponse(head, new byte[0]);
            return;
        }
        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            writeResponseFile(head, channel, offset, length, gzip);
        }
    }

    /**
     * Writes a file range from an already-open channel, taking ownership of
     * it (closed on success and failure). The range is not read when the head
     * sends no body bytes.
     */
    default void writeResponseFile(ResponseHead head, FileChannel channel,
            long offset, long length, boolean gzip) throws IOException {
        try {
            if (!head.sendsBodyBytes()) {
                writeResponse(head, new byte[0]);
                return;
            }
            if (length < 0 || length > Integer.MAX_VALUE) {
                throw new IOException("File range too large to buffer");
            }
            channel.position(offset);
            byte[] data = new byte[(int) length];
            int read = 0;
            while (read < data.length) {
                int n = channel.read(java.nio.ByteBuffer.wrap(data, read, data.length - read));
                if (n < 0) {
                    throw new IOException("File ended before the requested range");
                }
                read += n;
            }
            if (gzip) {
                writeResponse(head, new ByteArrayInputStream(data), true);
            } else {
                writeResponse(head, data);
            }
        } finally {
            channel.close();
        }
    }

    /** Writes the SSE head and returns an emitter over the response stream. */
    SseEmitter openSse(ResponseHead head) throws IOException;
}
