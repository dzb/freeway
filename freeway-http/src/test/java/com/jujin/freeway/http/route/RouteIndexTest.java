package com.jujin.freeway.http.route;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.Value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void encodedSlashStaysInsideOnePathSegment() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/{name}", ctx -> ctx.send(200, "ok"))),
            List.of());
        var match = registry.match("GET", "/files/a%2Fb");
        assertNotNull(match);
        assertEquals("a/b", match.pathVariables().get("name"));
    }

    @Test
    void constrainedParameterRoutesCanShareAPathLevel() {
        RouteIndex registry = new RouteIndex(List.of(
            Route.get("/items/{id:\\d+}", ctx -> ctx.send(200, "number")),
            Route.get("/items/{name:[a-z]+}", ctx -> ctx.send(200, "word"))), List.of());
        assertNotNull(registry.match("GET", "/items/42"));
        assertNotNull(registry.match("GET", "/items/abc"));
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

    @Test
    void rejectsEmptyParameterNameInHttpRouteRegistration() {
        assertThrows(IllegalArgumentException.class, () ->
            Route.get("/users/{}", ctx -> ctx.send(200, "ok")));
        assertThrows(IllegalArgumentException.class, () ->
            Route.get("/users/{:id}", ctx -> ctx.send(200, "ok")));
    }

    @Test
    void matchesEncodedLiteralSegments() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/hello world", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        assertNotNull(registry.match("GET", "/hello%20world"),
            "Encoded request path must match a decoded literal route");
    }

    @Test
    void pathVariablesAreDecoded() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/users/{name}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        RouteIndex.RouteMatch match = registry.match("GET", "/users/a%20b");
        assertNotNull(match);
        assertEquals("a b", match.pathVariables().get("name"),
            "Path variables must be percent-decoded");
    }

    @Test
    void encodedTraversalIsRejectedAtMatchTime() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/{name}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        assertNull(registry.match("GET", "/files/%2e%2e"));
        assertNull(registry.match("GET", "/files/..%2Fetc"));
    }

    @Test
    void malformedEncodingDoesNotMatch() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/{name}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        assertNull(registry.match("GET", "/files/%zz"));
    }

    // ──── handler class registration ────

    static class NoDepsHandler implements RouteHandler {
        @Override public void handle(HttpContext ctx) throws Exception {
            ctx.send(200, "ok");
        }
    }

    static class InjectedHandler implements RouteHandler {
        final String greeting;
        @Inject
        InjectedHandler(@Value("${greeting:Hello}") String greeting) {
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
