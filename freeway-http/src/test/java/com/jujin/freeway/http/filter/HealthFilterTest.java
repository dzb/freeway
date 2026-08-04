package com.jujin.freeway.http.filter;

import com.jujin.freeway.http.StubHttpContext;

import com.jujin.freeway.http.filter.HealthCheck;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.RouteHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthFilterTest {

    @Test
    void interceptsGetOnHealthPath() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/healthz", new HealthCheck.Default());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        StubHttpContext ctx = new StubHttpContext("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertEquals(200, ctx.status());
        assertTrue(ctx.responseBody().contains("status"));
        assertFalse(nextCalled.get());
    }

    @Test
    void passesThroughNonHealthPath() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/healthz", new HealthCheck.Default());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        StubHttpContext ctx = new StubHttpContext("GET", "/api/users");
        filter.doFilter(ctx, next);

        assertTrue(nextCalled.get());
    }

    @Test
    void passesThroughNonGetMethod() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/healthz", new HealthCheck.Default());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        StubHttpContext ctx = new StubHttpContext("POST", "/healthz");
        filter.doFilter(ctx, next);

        assertTrue(nextCalled.get());
    }

    @Test
    void disabledHealthCheckPassesThrough() throws Exception {
        HealthFilter filter = new HealthFilter(false, "/healthz", new HealthCheck.Default());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        StubHttpContext ctx = new StubHttpContext("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertTrue(nextCalled.get());
    }

    @Test
    void usesCustomHealthPath() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/ping", new HealthCheck.Default());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        StubHttpContext ctx = new StubHttpContext("GET", "/ping");
        filter.doFilter(ctx, next);

        assertEquals(200, ctx.status());
        assertFalse(nextCalled.get());
    }

    @Test
    void normalizesPathWithoutLeadingSlash() throws Exception {
        HealthFilter filter = new HealthFilter(true, "healthz", new HealthCheck.Default());
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        StubHttpContext ctx = new StubHttpContext("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertEquals(200, ctx.status());
    }

    @Test
    void usesCustomHealthCheck() throws Exception {
        HealthCheck custom = () -> Map.of("alive", true, "db", "ok");
        HealthFilter filter = new HealthFilter(true, "/healthz", custom);
        RouteHandler next = ctx -> {};

        StubHttpContext ctx = new StubHttpContext("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertEquals(200, ctx.status());
        assertTrue(ctx.responseBody().contains("db"));
    }

    @Test
    void defaultHealthCheckReturnsStatusOk() {
        HealthCheck health = new HealthCheck.Default();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) health.check();
        assertEquals("ok", result.get("status"));
    }
}
