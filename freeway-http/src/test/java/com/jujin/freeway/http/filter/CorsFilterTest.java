package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.StubHttpContext;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jujin.freeway.http.filter.CorsFilter;

class CorsFilterTest {
    @Test
    void addsHeadersForPreflight() throws Exception {
        CorsFilter filter = CorsFilter.builder().allowAllOrigins().build();
        StubHttpContext ctx = new StubHttpContext("OPTIONS", "/any")
            .requestHeader("Origin", "https://example.com")
            .requestHeader("Access-Control-Request-Method", "POST");

        filter.doFilter(ctx, next -> next.send(200, "ok"));

        assertEquals(204, ctx.status());
        assertEquals("*", ctx.responseHeader("Access-Control-Allow-Origin"));
        assertEquals("GET, POST, PUT, DELETE, PATCH, OPTIONS", ctx.responseHeader("Access-Control-Allow-Methods"));
    }

    @Test
    void noCorsHeadersOnPlainRequest() throws Exception {
        // A request without an Origin header is not a CORS request: the
        // filter must pass through without stamping CORS response headers.
        CorsFilter filter = CorsFilter.builder()
            .allowedOrigins("https://example.com")
            .allowCredentials(true)
            .build();
        StubHttpContext ctx = new StubHttpContext("GET", "/api");

        filter.doFilter(ctx, next -> next.send(200, "ok"));

        assertEquals(200, ctx.status());
        assertEquals(null, ctx.responseHeader("Access-Control-Allow-Origin"));
        assertEquals(null, ctx.responseHeader("Access-Control-Allow-Credentials"),
            "Credentials header must not leak onto non-CORS responses");
    }
}
