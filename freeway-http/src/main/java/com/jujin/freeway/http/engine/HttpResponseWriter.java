package com.jujin.freeway.http.engine;

import java.io.IOException;

import com.jujin.freeway.http.ResponseHead;
import com.jujin.freeway.http.sse.SseEmitter;

/**
 * Serializes one response for the built-in engine. The HTTP/1.x implementation
 * writes the status line and headers once, then streams body bytes; the HTTP/2
 * implementation frames the same {@link ResponseHead} as HEADERS/DATA on one
 * stream.
 *
 * <p>Writers are constructed per response (HTTP/1.x) or per stream (HTTP/2),
 * so framing state such as {@code headersWritten} and {@code chunked} is
 * owned by the response rather than by the connection. Every method receives
 * the immutable {@link ResponseHead} snapshot assembled by {@code
 * HttpContextSupport}.</p>
 */
public interface HttpResponseWriter {

    /** Writes the status line/headers and protocol framing, once. */
    void writeHead(ResponseHead head) throws IOException;

    /** Writes response body bytes. */
    default void writeBody(ResponseHead head, byte[] data) throws IOException {
        writeBody(head, data, 0, data.length);
    }

    /** Writes a body slice. */
    void writeBody(ResponseHead head, byte[] data, int offset, int length)
            throws IOException;

    /** Flushes / ends the response (HTTP/1.1 flush, HTTP/2 END_STREAM). */
    void end(ResponseHead head) throws IOException;

    /** Opens a Server-Sent Events emitter, writing the response head first. */
    SseEmitter openSse(ResponseHead head) throws IOException;

    /**
     * Invoked when the response body length violates the advertised
     * Content-Length after headers were written. The transport decides the
     * failure state: HTTP/1.1 closes the connection; HTTP/2 resets the
     * stream with PROTOCOL_ERROR (RFC 9113 §8.2.2).
     */
    default void onLengthMismatch(ResponseHead head) throws IOException {
        // HTTP/1.1: the transport has already marked the connection
        // non-reusable; there is nothing further to write.
    }
}
