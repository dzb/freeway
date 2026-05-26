package com.jujin.freeway.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorsFilterTest {
    @Test
    void addsHeadersForPreflight() throws Exception {
        CorsFilter filter = CorsFilter.builder().allowAllOrigins().build();
        StubHttpContext ctx = new StubHttpContext("OPTIONS", "/any")
            .header("Origin", "https://example.com");

        filter.doFilter(ctx, next -> next.send(200, "ok"));

        assertEquals(204, ctx.statusCode());
        assertEquals("*", ctx.header("Access-Control-Allow-Origin"));
        assertEquals("GET, POST, PUT, DELETE, PATCH, OPTIONS", ctx.header("Access-Control-Allow-Methods"));
    }
}
