package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.HttpResponse;
import com.jujin.freeway.http.HttpStatus;
import com.jujin.freeway.http.ValidationException;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.body.MultipartException;
import com.jujin.freeway.http.body.UnsupportedMediaTypeException;
import java.util.Map;

/**
 * Handles an exception thrown during request processing by writing an
 * HTTP error response.
 */
@FunctionalInterface
public interface ErrorHandler {
    /**
     * Attempts to handle the given exception by writing an error response
     * to the response.
     *
     * @return true if the exception was handled, false to delegate to the
     *         next mapper or the default error handler
     */
    boolean handle(HttpResponse response, Exception exception) throws Exception;

    /**
     * The built-in exception-to-response handler shared by the IoC
     * {@code HttpModule} and the standalone {@code WebServerBuilder}, so both
     * entry points produce identical error bodies: 413 for oversized bodies,
     * 415 for unsupported request media types, 400 for invalid multipart
     * requests and failed bean validation, and {@code false} for anything
     * else.
     */
    static ErrorHandler defaults() {
        return (ctx, ex) -> {
            if (ex instanceof BodyTooLargeException) {
                ctx.sendJson(HttpStatus.PAYLOAD_TOO_LARGE, Map.of(
                    "error", "Payload Too Large",
                    "message", ex.getMessage()));
                return true;
            }
            if (ex instanceof MultipartException) {
                ctx.sendJson(HttpStatus.BAD_REQUEST,
                    Map.of("error", "Invalid Multipart Request"));
                return true;
            }
            if (ex instanceof UnsupportedMediaTypeException) {
                ctx.sendJson(HttpStatus.UNSUPPORTED_MEDIA_TYPE, Map.of(
                    "error", "Unsupported Media Type",
                    "message", ex.getMessage()));
                return true;
            }
            if (ex instanceof ValidationException ve) {
                var errors = ve.result().errors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
                ctx.sendJson(HttpStatus.BAD_REQUEST, Map.of(
                    "error", "Validation Failed", "details", errors));
                return true;
            }
            return false;
        };
    }
}
