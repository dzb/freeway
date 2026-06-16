package com.jujin.freeway.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.http.internal.JdkHttpContext;

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
}
