package com.jujin.freeway.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One error body for every failure path. The route-miss and server-error
 * responses used to be {@code text/plain} while the exception mapper answered
 * JSON, so a client had to parse two shapes (and 404/405/500 carried no
 * structured reason at all).
 */
class ErrorResponsesTest {

    @Test
    void notFoundAnswersJson() throws Exception {
        StubHttpContext ctx = new StubHttpContext();
        ErrorResponses.notFound(ctx);

        assertEquals(404, ctx.status());
        assertTrue(ctx.responseHeader("Content-Type").startsWith("application/json"),
            "error bodies are JSON whatever produced them, got "
                + ctx.responseHeader("Content-Type"));
        assertTrue(ctx.responseBody().contains("\"error\":\"Not Found\""),
            "and carry the reason: " + ctx.responseBody());
    }

    @Test
    void methodNotAllowedKeepsAllowAndAnswersJson() throws Exception {
        StubHttpContext ctx = new StubHttpContext();
        ErrorResponses.methodNotAllowed(ctx, "GET, HEAD");

        assertEquals(405, ctx.status());
        assertEquals("GET, HEAD", ctx.responseHeader("Allow"),
            "RFC 9110 requires Allow on a 405");
        assertTrue(ctx.responseHeader("Content-Type").startsWith("application/json"),
            "the body shape matches every other error, got "
                + ctx.responseHeader("Content-Type"));
    }

    @Test
    void internalErrorAnswersJson() throws Exception {
        StubHttpContext ctx = new StubHttpContext();
        ErrorResponses.internalError(ctx);

        assertEquals(500, ctx.status());
        assertTrue(ctx.responseHeader("Content-Type").startsWith("application/json"),
            "got " + ctx.responseHeader("Content-Type"));
        assertTrue(ctx.responseBody().contains("\"error\":\"Internal Server Error\""),
            ctx.responseBody());
    }
}
