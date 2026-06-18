package com.jujin.freeway.http;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;


class HttpContextTest {
    @Test
    void maxBodySizeMustBePositive() {
        StubHttpContext ctx = new StubHttpContext();

        assertThrows(IllegalArgumentException.class, () -> ctx.maxBodySize(0));
        assertThrows(IllegalArgumentException.class, () -> ctx.maxBodySize(-1));
    }

    @Test
    void malformedQueryEncodingFallsBackToRawText() {
        var params = JdkHttpContext.parseQueryParams("bad=%zz&ok=a%20b");

        assertEquals("%zz", params.get("bad").getFirst());
        assertEquals("a b", params.get("ok").getFirst());
    }

    @Test
    void noContentStatusesSuppressResponseBody() throws IOException {
        StubHttpContext noContent = new StubHttpContext();
        noContent.send(204, "ignored");
        assertEquals(204, noContent.statusCode());
        assertEquals("", noContent.responseBody());
        assertNull(noContent.header("Content-Type"));

        StubHttpContext resetContent = new StubHttpContext();
        resetContent.send(205, "ignored");
        assertEquals(205, resetContent.statusCode());
        assertEquals("", resetContent.responseBody());
        assertNull(resetContent.header("Content-Type"));

        StubHttpContext notModified = new StubHttpContext();
        notModified.status(304).outputJson(Map.of("ok", true));
        assertEquals(304, notModified.statusCode());
        assertEquals("", notModified.responseBody());
        assertNull(notModified.header("Content-Type"));
    }
}
