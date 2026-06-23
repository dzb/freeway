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
}
