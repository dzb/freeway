package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.ValidationException;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.body.MultipartException;

import java.util.Map;

/**
 * Built-in exception-to-response mapping shared by the IoC
 * {@code HttpModule} and the standalone {@code WebServerBuilder}, so both
 * entry points produce identical error bodies.
 */
public final class ExceptionMappers {

    private ExceptionMappers() {}

    /** The default mapper: 413 for oversized bodies, 400 for invalid
     *  multipart requests and failed bean validation. */
    public static ExceptionMapper defaultMapper() {
        return (ctx, ex) -> {
            if (ex instanceof BodyTooLargeException) {
                ctx.sendJson(413, Map.of(
                    "error", "Payload Too Large",
                    "message", ex.getMessage()));
                return true;
            }
            if (ex instanceof MultipartException) {
                ctx.sendJson(400, Map.of("error", "Invalid Multipart Request"));
                return true;
            }
            if (ex instanceof ValidationException ve) {
                var errors = ve.result().getErrors().stream()
                    .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
                ctx.sendJson(400, Map.of(
                    "error", "Validation Failed", "details", errors));
                return true;
            }
            return false;
        };
    }
}
