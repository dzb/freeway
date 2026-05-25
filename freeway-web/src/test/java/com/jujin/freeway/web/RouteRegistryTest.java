package com.jujin.freeway2.web;

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
}
