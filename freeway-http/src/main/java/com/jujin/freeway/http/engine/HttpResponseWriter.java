package com.jujin.freeway.http.engine;

import java.io.IOException;

import com.jujin.freeway.http.sse.SseEmitter;

/**
 * Writes an HTTP response for a {@link HttpContextImpl}. One implementation
 * serializes HTTP/1.x onto a raw socket; the HTTP/2 implementation frames
 * HEADERS/DATA onto a stream. The context owns the response state (status,
 * response headers, method, keep-alive); writers read it through the public
 * {@link HttpContextImpl} accessors.
 *
 * <p>This replaces the former {@code Http2ResponseBridge}: instead of a bare
 * header map the writer is a behavior contract covering head, body, end, and
 * SSE — the context no longer branches on transport mode.</p>
 */
public interface HttpResponseWriter {

    /** Writes the response head (status line + headers). */
    void writeHead(HttpContextImpl ctx) throws IOException;

    /** Writes response body bytes (no-op for HEAD requests when no body is allowed). */
    void writeBody(HttpContextImpl ctx, byte[] data) throws IOException;

    /** Writes a body slice. Default impl copies; transports override for
     *  zero-copy streaming. */
    default void writeBody(HttpContextImpl ctx, byte[] data, int offset, int length)
            throws IOException {
        if (offset == 0 && length == data.length) {
            writeBody(ctx, data);
            return;
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        writeBody(ctx, copy);
    }

    /** Flushes / ends the response (HTTP/1.1 flush, HTTP/2 END_STREAM). */
    void end(HttpContextImpl ctx) throws IOException;

    /** Opens a Server-Sent Events emitter, writing the response head first. */
    SseEmitter openSse(HttpContextImpl ctx) throws IOException;

    /**
     * Invoked when the response body length violates the advertised
     * Content-Length after headers were written. The transport decides the
     * failure state: HTTP/1.1 closes the connection; HTTP/2 resets the
     * stream with PROTOCOL_ERROR (RFC 9113 §8.2.2).
     */
    default void onLengthMismatch(HttpContextImpl ctx) throws IOException {
        // HTTP/1.1: the context has already marked the connection
        // non-reusable; there is nothing further to write.
    }
}
