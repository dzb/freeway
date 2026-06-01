package com.jujin.freeway.http;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteIndexTest {
    @Test
    void matchesPathParameters() {
        RouteIndex registry = new RouteIndex(List.of(
            Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))
        ), List.of());

        RouteIndex.RouteMatch match = registry.match("GET", "/users/42");
        assertTrue(match != null);
        assertEquals("42", match.pathVariables().get("id"));
    }

    @Test
    void rejectsDuplicateRoutes() {
        assertThrows(IllegalStateException.class, () -> new RouteIndex(List.of(
            Route.get("/users/{id}", ctx -> ctx.send(200, "ok")),
            Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))
        ), List.of()));
    }

    @Test
    void rejectsEncodedTraversalInHttpRouteRegistration() {
        assertThrows(IllegalArgumentException.class, () ->
            Route.get("/files/%2e%2e", ctx -> ctx.send(200, "ok")));
    }

    @Test
    void rejectsEncodedTraversalInWebSocketRouteRegistration() {
        assertThrows(IllegalArgumentException.class, () ->
            WebSocketRoute.of("/ws/%2e%2e", session -> WebSocketListener.NOOP));
    }
}
