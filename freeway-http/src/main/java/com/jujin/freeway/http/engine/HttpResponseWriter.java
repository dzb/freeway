package com.jujin.freeway.http.engine;

import com.jujin.freeway.http.sse.SseEmitter;

import java.io.IOException;

/**
 * Writes an HTTP response for a {@link HttpContextDefault}. One implementation
 * serializes HTTP/1.1 onto a raw socket; the HTTP/2 implementation frames
 * HEADERS/DATA onto a stream. The context owns the response state (status,
 * response headers, method, keep-alive); writers read it through the public
 * {@link HttpContextDefault} accessors.
 *
 * <p>This replaces the former {@code Http2ResponseBridge}: instead of a bare
 * header map the writer is a behavior contract covering head, body, end, and
 * SSE — the context no longer branches on transport mode.</p>
 */
public interface HttpResponseWriter {

    /** Writes the response head (status line + headers). */
    void writeHead(HttpContextDefault ctx) throws IOException;

    /** Writes response body bytes (no-op for HEAD requests when no body is allowed). */
    void writeBody(HttpContextDefault ctx, byte[] data) throws IOException;

    /** Writes a body slice. Default impl copies; transports override for
     *  zero-copy streaming. */
    default void writeBody(HttpContextDefault ctx, byte[] data, int offset, int length)
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
    void end(HttpContextDefault ctx) throws IOException;

    /** Opens a Server-Sent Events emitter, writing the response head first. */
    SseEmitter openSse(HttpContextDefault ctx) throws IOException;
}
