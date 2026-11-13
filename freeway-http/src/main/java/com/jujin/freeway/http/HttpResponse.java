package com.jujin.freeway.http;

import com.jujin.freeway.http.sse.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * Write side of an HTTP exchange: status, headers, bodies, files, and SSE.
 * Application handlers receive the combined {@link HttpExchange}; framework
 * components that only write (exception mappers, static files) should depend
 * on this interface.
 */
public interface HttpResponse {

    /**
     * Sets the HTTP response status code.
     *
     * @return this response for chaining
     */
    HttpResponse setStatus(int status);

    /** Returns the HTTP response status code. */
    int status();

    /**
     * Sets a response header, replacing all existing values for the name.
     *
     * @return this response for chaining
     */
    HttpResponse setHeader(String name, String value);

    /**
     * Adds a value to a response header, preserving any existing values for
     * the same name so the field can be sent multiple times (for example,
     * multiple {@code Set-Cookie} or {@code WWW-Authenticate} fields).
     *
     * <p>Implementations are expected to preserve every value as a distinct
     * field on the wire so repeated fields such as {@code Set-Cookie} remain
     * separate.</p>
     *
     * @return this response for chaining
     */
    HttpResponse addHeader(String name, String value);

    /**
     * Adds a token to the {@code Vary} response header, merging with any
     * existing value without duplicating the token (case-insensitive).
     * Like {@link #setHeader}, this is a no-op once the response has been
     * committed.
     *
     * @return this response for chaining
     * @throws IllegalArgumentException if the token is null or blank
     */
    HttpResponse addVary(String token);

    /**
     * Sends a response with the given status code and binary body.
     *
     * @return this response for chaining
     */
    HttpResponse body(byte[] data) throws IOException;

    /**
     * Sends the remaining bytes of the given stream as the response body.
     * {@code contentLength} is the source length, or {@code -1} when unknown.
     * A non-negative value becomes the framework-authoritative
     * {@code Content-Length} when the body is not compressed; with compression
     * the transport owns the encoded length and this value is only a source
     * hint.
     */
    HttpResponse body(InputStream in, long contentLength) throws IOException;

    /**
     * Sends a byte range of a file. Implementations may stream the range or
     * use a zero-copy/sendfile path.
     */
    HttpResponse file(Path file, long offset, long length) throws IOException;

    /**
     * Streams an already-open file channel as the response body, taking
     * ownership of the channel (the implementation closes it on success and
     * failure). Mainly used by the static-file layer to hand over a securely
     * opened channel for the sendfile fast path; application handlers can
     * use {@link #file(Path, long, long)} instead.
     */
    HttpResponse file(FileChannel channel, long offset, long length)
            throws IOException;

    /**
     * Sends a response with the given status code and text body.
     * Content-Type defaults to text/plain if not already set.
     *
     * @return this response for chaining
     */
    HttpResponse text(String text) throws IOException;

    /**
     * Sends a JSON response for the given value.
     * Content-Type defaults to application/json if not already set.
     *
     * @return this response for chaining
     */
    HttpResponse json(Object value) throws IOException;

    /**
     * Sends a response with the given status code and text body.
     * Convenience shorthand for {@code setStatus(s).text(t)}.
     *
     * @return this response for chaining
     */
    HttpResponse sendText(int status, String text) throws IOException;

    /**
     * Sends a JSON response with the given status code.
     * Convenience shorthand for {@code setStatus(s).json(v)}.
     *
     * @return this response for chaining
     */
    HttpResponse sendJson(int status, Object value) throws IOException;

    /**
     * Opens an SSE (Server-Sent Events) emitter on this response.
     * The response headers must be set before calling this method.
     */
    SseEmitter sse() throws IOException;

    /**
     * True once the response has been committed: headers or body have begun
     * being written to the transport. Once committed, a subsequent transport
     * failure (peer disconnect) can no longer be turned into an error response.
     */
    boolean isCommitted();

    /** Returns true if the response status allows a body. */
    default boolean allowsBody() {
        return HttpStatus.allowsBody(status());
    }
}
