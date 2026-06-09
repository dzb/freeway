package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Extension;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteIndexTest {
    @Test
    void matchesPathParameters() {
        RouteIndex registry = new RouteIndex(
            Extension.of(Route.class, Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))),
            Extension.of(RouteGroup.class)
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/users/42");
        assertTrue(match != null);
        assertEquals("42", match.pathVariables().get("id"));
    }

    @Test
    void nonTerminalDotStarConstraintMatchesOnlyOneSegment() {
        RouteIndex registry = new RouteIndex(
            Extension.of(Route.class, Route.get("/files/{name:.*}/meta", ctx -> ctx.send(200, "ok"))),
            Extension.of(RouteGroup.class)
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/files/readme/meta");
        assertTrue(match != null);
        assertEquals("readme", match.pathVariables().get("name"));
        assertNull(registry.match("GET", "/files/a/b/meta"));
    }

    @Test
    void terminalDotStarConstraintConsumesRemainingSegments() {
        RouteIndex registry = new RouteIndex(
            Extension.of(Route.class, Route.get("/files/{path:.*}", ctx -> ctx.send(200, "ok"))),
            Extension.of(RouteGroup.class)
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/files/a/b/c.txt");
        assertTrue(match != null);
        assertEquals("a/b/c.txt", match.pathVariables().get("path"));
    }

    @Test
    void rejectsEmptyPathParameterSegments() {
        RouteIndex registry = new RouteIndex(
            Extension.of(Route.class, Route.get("/users/{id}/profile", ctx -> ctx.send(200, "ok"))),
            Extension.of(RouteGroup.class)
        );

        assertNull(registry.match("GET", "/users//profile"));
    }

    @Test
    void rejectsDuplicateRoutes() {
        assertThrows(IllegalStateException.class, () -> new RouteIndex(
            Extension.of(Route.class, Route.get("/users/{id}", ctx -> ctx.send(200, "ok")), Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))),
            Extension.of(RouteGroup.class)
        ));
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
