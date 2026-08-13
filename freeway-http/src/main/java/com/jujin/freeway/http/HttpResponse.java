package com.jujin.freeway.http;

import com.jujin.freeway.http.sse.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Write side of an HTTP exchange: status, headers, bodies, files, and SSE.
 * Application handlers receive the combined {@link HttpContext}; framework
 * components that only write (exception mappers, static files) should depend
 * on this interface.
 */
public interface HttpResponse {

    /**
     * Sets the HTTP response status code.
     *
     * @return this response for chaining
     */
    HttpResponse status(int status);

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
     * @throws IllegalArgumentException if the token is null or blank
     */
    void addVary(String token);

    /**
     * Sends a response with the given status code and binary body.
     *
     * @return this response for chaining
     */
    HttpResponse output(byte[] data) throws IOException;

    /**
     * Streams a response body from an input stream, writing the response head
     * first. {@code contentLength} must be known (or a Content-Length header
     * pre-set) so HTTP/1.1 can frame the response.
     */
    /**
     * Sends the remaining bytes of the given stream as the response body.
     * {@code contentLength} is advisory for implementations that can stream
     * with a known length; the default buffers the stream and delegates to
     * {@link #output(byte[])}.
     */
    default HttpResponse output(InputStream in, long contentLength)
            throws IOException {
        return output(in.readAllBytes());
    }

    /**
     * Sends a byte range of a file. Uses the OS sendfile path when the
     * transport supports it (plain HTTP/1.1 socket with a channel and no
     * compression); otherwise falls back to buffered streaming.
     */
    /**
     * Sends a byte range of a file. The default buffers the range and
     * delegates to {@link #output(byte[])}; transports with a zero-copy or
     * sendfile path override it.
     */
    default HttpResponse outputFile(Path file, long offset, long length)
            throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            input.skipNBytes(offset);
            if (length > Integer.MAX_VALUE) {
                throw new IOException("File range too large to buffer");
            }
            return output(input.readNBytes((int) length));
        }
    }

    /**
     * Streams an already-open file channel as the response body, taking
     * ownership of the channel (the implementation closes it on success and
     * failure). Mainly used by the static-file layer to hand over a securely
     * opened channel for the sendfile fast path; application handlers can
     * use {@link #outputFile(Path, long, long)} instead.
     */
    default HttpResponse outputFile(FileChannel channel, long offset, long length)
            throws IOException {
        throw new UnsupportedOperationException(
            "File-channel output is not supported");
    }

    /**
     * Sends a response with the given status code and text body.
     * Content-Type defaults to text/plain if not already set.
     *
     * @return this response for chaining
     */
    HttpResponse output(String text) throws IOException;

    /**
     * Sends a JSON response for the given value.
     * Content-Type defaults to application/json if not already set.
     *
     * @return this response for chaining
     */
    HttpResponse outputJson(Object value) throws IOException;

    /**
     * Sends a response with the given status code and text body.
     * Convenience shorthand for {@code status(s).output(t)}.
     *
     * @return this response for chaining
     */
    HttpResponse send(int status, String text) throws IOException;

    /**
     * Sends a JSON response with the given status code.
     * Convenience shorthand for {@code status(s).outputJson(v)}.
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
     * Whether the response has been committed (headers or body have started
     * being written to the transport). Once committed, a subsequent transport
     * failure (peer disconnect) can no longer be turned into an error response.
     */
    default boolean isResponded() {
        return false;
    }

    /** Returns true if the response status allows a body. */
    default boolean allowsResponseBody() {
        int status = status();
        return status != 204 && status != 205 && status != 304;
    }

    /**
     * Returns true when the wire must not carry body bytes for this response
     * and method: HEAD requests and the bodyless statuses already excluded by
     * {@link #allowsResponseBody()}.
     */
    default boolean suppressBodyBytes(String method) {
        return !allowsResponseBody() || "HEAD".equalsIgnoreCase(method);
    }
}
