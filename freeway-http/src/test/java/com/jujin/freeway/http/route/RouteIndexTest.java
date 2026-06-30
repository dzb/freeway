package com.jujin.freeway.http.route;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteIndexTest {

    @Test
    void matchesPathParameters() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        RouteIndex.RouteMatch match = registry.match("GET", "/users/42");
        assertNotNull(match);
        assertEquals("42", match.pathVariables().get("id"));
    }

    @Test
    void nonTerminalDotStarConstraintMatchesOnlyOneSegment() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/{name:.*}/meta", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        assertNotNull(registry.match("GET", "/files/readme/meta"));
        assertNull(registry.match("GET", "/files/a/b/meta"));
    }

    @Test
    void terminalDotStarConstraintConsumesRemainingSegments() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/{path:.*}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        RouteIndex.RouteMatch match = registry.match("GET", "/files/a/b/c.txt");
        assertNotNull(match);
        assertEquals("a/b/c.txt", match.pathVariables().get("path"));
    }

    @Test
    void rejectsEmptyPathParameterSegments() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/users/{id}/profile", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        assertNull(registry.match("GET", "/users//profile"));
    }

    @Test
    void rejectsDuplicateRoutes() {
        assertThrows(IllegalStateException.class, () -> new RouteIndex(
            List.of(Route.get("/users/{id}", ctx -> ctx.send(200, "ok")), Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))),
            List.of()
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

    // ──── handler class registration ────

    static class NoDepsHandler implements RouteHandler {
        @Override public void handle(HttpContext ctx) throws Exception {
            ctx.send(200, "ok");
        }
    }

    static class InjectedHandler implements RouteHandler {
        final String greeting;
        @com.jujin.freeway.ioc.annotation.Inject
        InjectedHandler(@com.jujin.freeway.ioc.annotation.Value("${greeting:Hello}") String greeting) {
            this.greeting = greeting;
        }
        @Override public void handle(HttpContext ctx) throws Exception {
            ctx.send(200, greeting);
        }
    }

    @Test
    void handlerClassResolvedFromContainer() {
        Container container = Freeway.create(b -> {
            b.bind(RouteIndex.class).to(RouteIndex.class);
            b.contribute(Route.class).add(Route.get("/test", NoDepsHandler.class));
        });
        RouteIndex index = container.get(RouteIndex.class);
        assertNotNull(index.match("GET", "/test"));
    }

    @Test
    void handlerClassLazyResolutionInStandaloneMode() {
        RouteIndex index = new RouteIndex(
            List.of(Route.get("/test", NoDepsHandler.class)),
            List.of()
        );
        // LazyHandler -- matched route is returned, resolution deferred
        assertNotNull(index.match("GET", "/test"));
    }

    @Test
    void handlerClassReceivesConstructorInjection() {
        Container container = Freeway.create(b -> {
            b.bind(RouteIndex.class).to(RouteIndex.class);
            b.contribute(Route.class).add(Route.get("/greet", InjectedHandler.class));
        });
        RouteIndex index = container.get(RouteIndex.class);
        assertNotNull(index.match("GET", "/greet"));
    }

    @Test
    void handlerClassInRouteGroupIsResolved() {
        Container container = Freeway.create(b -> {
            b.bind(RouteIndex.class).to(RouteIndex.class);
            b.contribute(RouteGroup.class).add(
                RouteGroup.of("/api", Route.get("/health", NoDepsHandler.class))
            );
        });
        RouteIndex index = container.get(RouteIndex.class);
        assertNotNull(index.match("GET", "/api/health"));
    }
}
