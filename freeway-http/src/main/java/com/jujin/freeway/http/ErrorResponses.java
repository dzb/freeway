package com.jujin.freeway.http;

import java.util.Map;

/**
 * Factories for the common route-miss, method-not-allowed and internal-error
 * responses. They are pure values; {@link ErrorResponse#write(HttpResponse)} is
 * the one place that writes them.
 */
public final class ErrorResponses {

    private ErrorResponses() {}

    public static ErrorResponse notFound() {
        return ErrorResponse.of(HttpStatus.NOT_FOUND, "not_found", "Not Found");
    }

    /**
     * RFC 9110 requires {@code Allow} on a 405 — it answers "why did POST
     * 404?". The header carries the protocol fact; the details map keeps the
     * same fact readable in JSON.
     *
     * @param allow comma-separated methods, e.g. {@code "GET, HEAD"}
     */
    public static ErrorResponse methodNotAllowed(String allow) {
        return ErrorResponse.of(HttpStatus.METHOD_NOT_ALLOWED,
                "method_not_allowed", "Method Not Allowed")
            .withHeader("Allow", allow)
            .withDetails(Map.of("allow", allow));
    }

    public static ErrorResponse internalError() {
        return ErrorResponse.of(HttpStatus.INTERNAL_ERROR,
            "internal_error", "Internal Server Error");
    }
}
