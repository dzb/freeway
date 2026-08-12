package com.jujin.freeway.http;

import com.jujin.freeway.http.sse.SseEmitter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Map;

/**
 * Combined HTTP request/response view handed to application handlers: one
 * object for reading the request and writing the response, keeping handler
 * lambdas concise. The read side is {@link HttpRequest} and the write side
 * {@link HttpResponse}; framework components that need only one side should
 * depend on the narrower interface.
 *
 * <p>Implementations normally extend {@link AbstractHttpContext}, which
 * provides the shared coercion, path-variable, and convenience logic.</p>
 */
public interface HttpContext extends HttpRequest, HttpResponse {

    /** Sets all path variables from a route match. Returns this for chaining. */
    @Override
    HttpContext pathVars(Map<String, String> vars);

    /** Sets the maximum request body size. Returns this for chaining. */
    @Override
    HttpContext maxBodySize(long maxBodySize);

    /** Sets the HTTP response status code. Returns this for chaining. */
    @Override
    HttpContext status(int status);

    /** Sets a response header. Returns this for chaining. */
    @Override
    HttpContext setHeader(String name, String value);

    /** Sends a binary body. Returns this for chaining. */
    @Override
    HttpContext output(byte[] data) throws IOException;

    /** Streams a body. Returns this for chaining. */
    @Override
    HttpContext output(InputStream in, long contentLength) throws IOException;

    /** Sends a file range. Returns this for chaining. */
    @Override
    HttpContext outputFile(Path file, long offset, long length)
        throws IOException;

    /** Streams an open file channel. Returns this for chaining. */
    @Override
    default HttpContext outputFile(FileChannel channel, long offset, long length)
            throws IOException {
        throw new UnsupportedOperationException(
            "File-channel output is not supported");
    }

    /** Sends a text body. Returns this for chaining. */
    @Override
    HttpContext output(String text) throws IOException;

    /** Sends a JSON body. Returns this for chaining. */
    @Override
    HttpContext outputJson(Object value) throws IOException;

    /** Sends a status + text body. Returns this for chaining. */
    @Override
    HttpContext send(int status, String text) throws IOException;

    /** Sends a status + JSON body. Returns this for chaining. */
    @Override
    HttpContext sendJson(int status, Object value) throws IOException;

    /** Opens an SSE emitter on this response. */
    @Override
    SseEmitter sse() throws IOException;
}
