package com.jujin.freeway.http;

import java.io.IOException;
import java.util.Map;

/**
 * Shared error responses for the common failure paths (route miss, a
 * method that is not allowed, and an unhandled exception).
 *
 * <p><b>One body shape for every error.</b> These used to be
 * {@code text/plain} while the exception mapper's 400/413/415 were JSON, so a
 * client could not parse errors uniformly. All error responses are now JSON
 * objects with an {@code error} key; the 405 additionally carries the
 * RFC 9110 {@code Allow} header, which is what explains the rejection.</p>
 */
public final class ErrorResponses {

    private ErrorResponses() {}

    public static void notFound(HttpResponse response) throws IOException {
        response.sendJson(HttpStatus.NOT_FOUND, Map.of("error", "Not Found"));
    }

    /**
     * RFC 9110 requires {@code Allow} on a 405 — it answers "why did POST
     * 404?".
     *
     * @param allow comma-separated methods, e.g. {@code "GET, HEAD"}
     */
    public static void methodNotAllowed(HttpResponse response, String allow)
            throws IOException {
        response.setHeader("Allow", allow)
            .sendJson(HttpStatus.METHOD_NOT_ALLOWED,
                Map.of("error", "Method Not Allowed", "allow", allow));
    }

    public static void internalError(HttpResponse response) throws IOException {
        response.sendJson(HttpStatus.INTERNAL_ERROR,
            Map.of("error", "Internal Server Error"));
    }
}
