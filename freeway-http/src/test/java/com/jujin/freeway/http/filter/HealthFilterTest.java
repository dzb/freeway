package com.jujin.freeway.http.filter;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HealthResult;
import com.jujin.freeway.http.TestExchange;
import com.jujin.freeway.http.filter.HealthCheck;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.route.RouteHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthFilterTest {

    @Test
    void interceptsGetOnHealthPath() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/healthz", HealthCheck.ALWAYS_OK);
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        TestExchange ctx = new TestExchange("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertEquals(200, ctx.status());
        assertTrue(ctx.responseBody().contains("status"));
        assertFalse(nextCalled.get());
    }

    @Test
    void passesThroughNonHealthPath() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/healthz", HealthCheck.ALWAYS_OK);
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        TestExchange ctx = new TestExchange("GET", "/api/users");
        filter.doFilter(ctx, next);

        assertTrue(nextCalled.get());
    }

    @Test
    void passesThroughNonGetMethod() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/healthz", HealthCheck.ALWAYS_OK);
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        TestExchange ctx = new TestExchange("POST", "/healthz");
        filter.doFilter(ctx, next);

        assertTrue(nextCalled.get());
    }

    @Test
    void disabledHealthCheckPassesThrough() throws Exception {
        HealthFilter filter = new HealthFilter(false, "/healthz", HealthCheck.ALWAYS_OK);
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        TestExchange ctx = new TestExchange("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertTrue(nextCalled.get());
    }

    @Test
    void usesCustomHealthPath() throws Exception {
        HealthFilter filter = new HealthFilter(true, "/ping", HealthCheck.ALWAYS_OK);
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        TestExchange ctx = new TestExchange("GET", "/ping");
        filter.doFilter(ctx, next);

        assertEquals(200, ctx.status());
        assertFalse(nextCalled.get());
    }

    @Test
    void normalizesPathWithoutLeadingSlash() throws Exception {
        HealthFilter filter = new HealthFilter(true, "healthz", HealthCheck.ALWAYS_OK);
        AtomicBoolean nextCalled = new AtomicBoolean(false);
        RouteHandler next = ctx -> nextCalled.set(true);

        TestExchange ctx = new TestExchange("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertEquals(200, ctx.status());
    }

    @Test
    void customUnhealthyCheckAnswers503() throws Exception {
        HealthCheck custom = () -> HealthResult.unhealthy("db down");
        HealthFilter filter = new HealthFilter(true, "/healthz", custom);
        RouteHandler next = ctx -> {};

        TestExchange ctx = new TestExchange("GET", "/healthz");
        filter.doFilter(ctx, next);

        assertEquals(503, ctx.status());
        assertTrue(ctx.responseBody().contains("db down"));
    }

    @Test
    void defaultHealthCheckIsHealthy() {
        assertTrue(HealthCheck.ALWAYS_OK.check().healthy());
    }
}
