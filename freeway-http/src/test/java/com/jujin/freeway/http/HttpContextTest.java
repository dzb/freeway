package com.jujin.freeway.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.body.UnsupportedMediaTypeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpContextTest {
    @Test
    void maxBodySizeMustBePositive() {
        StubHttpContext ctx = new StubHttpContext();

        assertThrows(IllegalArgumentException.class, () -> ctx.maxBodySize(0));
        assertThrows(IllegalArgumentException.class, () -> ctx.maxBodySize(-1));
    }

    @Test
    void malformedQueryEncodingFallsBackToRawText() {
        var params = HttpUtils.parseQueryParams("bad=%zz&ok=a%20b");

        assertEquals("%zz", params.get("bad").getFirst());
        assertEquals("a b", params.get("ok").getFirst());
    }

    @Test
    void noContentStatusesSuppressResponseBody() throws IOException {
        StubHttpContext noContent = new StubHttpContext();
        noContent.send(204, "ignored");
        assertEquals(204, noContent.status());
        assertEquals("", noContent.responseBody());
        assertNull(noContent.responseHeader("Content-Type"));

        StubHttpContext resetContent = new StubHttpContext();
        resetContent.send(205, "ignored");
        assertEquals(205, resetContent.status());
        assertEquals("", resetContent.responseBody());
        assertNull(resetContent.responseHeader("Content-Type"));

        StubHttpContext notModified = new StubHttpContext();
        notModified.status(304).outputJson(Map.of("ok", true));
        assertEquals(304, notModified.status());
        assertEquals("", notModified.responseBody());
        assertNull(notModified.responseHeader("Content-Type"));
    }

    @Test
    void outputStringDefaultsContentTypeWhenAbsent() throws IOException {
        StubHttpContext ctx = new StubHttpContext();

        ctx.output("hello");

        assertEquals("hello", ctx.responseBody());
        assertEquals("text/plain; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void outputJsonDefaultsContentTypeWhenAbsent() throws IOException {
        StubHttpContext ctx = new StubHttpContext();

        ctx.outputJson(Map.of("ok", true));

        assertEquals("application/json; charset=utf-8", ctx.responseHeader("Content-Type"));
    }

    @Test
    void requestHeadersAreExposedAsMap() {
        StubHttpContext ctx = new StubHttpContext()
            .requestHeader("X-Test", "a")
            .requestHeader("X-Test", "b");

        assertEquals(Map.of("X-Test", List.of("a", "b")), ctx.headers());
    }

    @Test
    void addVaryMergesAndDeduplicates() {
        StubHttpContext ctx = new StubHttpContext();

        ctx.addVary("Origin");
        ctx.addVary("accept-encoding");
        ctx.addVary("Origin"); // duplicate, case-insensitive

        assertEquals("Origin, accept-encoding", ctx.responseHeader("Vary"));
    }

    @Test
    void addVaryRejectsBlankToken() {
        StubHttpContext ctx = new StubHttpContext();

        assertThrows(IllegalArgumentException.class, () -> ctx.addVary(null));
        assertThrows(IllegalArgumentException.class, () -> ctx.addVary(" "));
    }

    @Test
    void headerNamesMustBeRfc7230Tokens() {
        assertThrows(IllegalArgumentException.class,
            () -> AbstractHttpContext.validateHeaderName("Bad Name"));
        assertThrows(IllegalArgumentException.class,
            () -> AbstractHttpContext.validateHeaderName("Bad:Name"));
        assertThrows(IllegalArgumentException.class,
            () -> AbstractHttpContext.validateHeaderName("Bad\u00e9"));
        AbstractHttpContext.validateHeaderName("X-Custom-1"); // valid token
    }

    @Test
    void bodyAsJsonRejectsNonJsonContentType() {
        StubHttpContext ctx = new StubHttpContext("POST", "/")
            .requestHeader("Content-Type", "text/plain");

        assertThrows(UnsupportedMediaTypeException.class,
            () -> ctx.bodyAsJson(Map.class));
    }
}
