package com.jujin.freeway.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Shared error responses for the common failure paths (route miss and
 * unhandled exceptions). Keeps the body, Content-Type, and status in one
 * place so the dispatcher, protocol sessions, and static-file fallback do
 * not drift.
 */
public final class ErrorResponses {

    private static final byte[] NOT_FOUND =
        "Not Found".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTERNAL_ERROR =
        "Internal Server Error".getBytes(StandardCharsets.UTF_8);

    private ErrorResponses() {}

    public static void notFound(HttpResponse response) throws IOException {
        response.setStatus(HttpStatus.NOT_FOUND)
            .setHeader("Content-Type", MediaTypes.TEXT_PLAIN_UTF8)
            .output(NOT_FOUND);
    }

    public static void internalError(HttpResponse response) throws IOException {
        response.setStatus(HttpStatus.INTERNAL_ERROR)
            .setHeader("Content-Type", MediaTypes.TEXT_PLAIN_UTF8)
            .output(INTERNAL_ERROR);
    }
}
