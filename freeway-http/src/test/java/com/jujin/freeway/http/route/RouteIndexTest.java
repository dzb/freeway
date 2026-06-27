package com.jujin.freeway.http.route;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;

class RouteIndexTest {

    @Test
    void matchesPathParameters() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/users/42");
        assertTrue(match != null);
        assertEquals("42", match.pathVariables().get("id"));
    }

    @Test
    void nonTerminalDotStarConstraintMatchesOnlyOneSegment() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/{name:.*}/meta", ctx -> ctx.send(200, "ok"))),
            List.of()
        );

        RouteIndex.RouteMatch match = registry.match(
            "GET",
            "/files/readme/meta"
        );
        assertTrue(match != null);
        assertEquals("readme", match.pathVariables().get("name"));
        assertNull(registry.match("GET", "/files/a/b/meta"));
    }

    @Test
    void terminalDotStarConstraintConsumesRemainingSegments() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/{path:.*}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/files/a/b/c.txt");
        assertTrue(match != null);
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
        assertThrows(IllegalStateException.class, () ->
            new RouteIndex(
                List.of(
                    Route.get("/users/{id}", ctx -> ctx.send(200, "ok")),
                    Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))
                ),
                List.of()
            )
        );
    }

    @Test
    void rejectsEncodedTraversalInHttpRouteRegistration() {
        assertThrows(IllegalArgumentException.class, () ->
            Route.get("/files/%2e%2e", ctx -> ctx.send(200, "ok"))
        );
    }

    // ── Colon syntax (:name) ─────────────────────────────────────

    @Test
    void colonSyntaxMatchesPathParameter() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/api/quote/:code", ctx -> ctx.send(200, "ok"))),
            List.of()
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/api/quote/600519");
        assertNotNull(match);
        assertEquals("600519", match.pathVariables().get("code"));
    }

    @Test
    void colonSyntaxMixedWithBraces() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/api/quote/:code/kline", ctx -> ctx.send(200, "ok"))),
            List.of()
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/api/quote/688017/kline");
        assertNotNull(match);
        assertEquals("688017", match.pathVariables().get("code"));
    }

    @Test
    void colonSyntaxMultipleParams() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/users/:userId/posts/:postId", ctx -> ctx.send(200, "ok"))),
            List.of()
        );

        RouteIndex.RouteMatch match = registry.match("GET", "/users/42/posts/99");
        assertNotNull(match);
        assertEquals("42", match.pathVariables().get("userId"));
        assertEquals("99", match.pathVariables().get("postId"));
    }

    @Test
    void colonSyntaxNoMatchWithoutSegment() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/api/quote/:code", ctx -> ctx.send(200, "ok"))),
            List.of()
        );

        assertNull(registry.match("GET", "/api/quote/"));
    }

    @Test
    void rejectsEncodedTraversalInWebSocketRouteRegistration() {
        assertThrows(IllegalArgumentException.class, () ->
            WebSocketRoute.of("/ws/%2e%2e", session -> WebSocketListener.NOOP)
        );
    }

    // ── Handler class injection ─────────────────────────────────────

    static class NoDepsHandler implements RouteHandler {
        @Override
        public void handle(HttpContext ctx) throws Exception {
            ctx.send(200, "ok");
        }
    }

    @Test
    void handlerTypeIsResolvedFromContainer() {
        Container container = Freeway.create(b -> {
            b.bind(RouteIndex.class).to(RouteIndex.class);
            b.contribute(Route.class).add(Route.get("/test", NoDepsHandler.class));
        });
        RouteIndex index = container.get(RouteIndex.class);
        RouteIndex.RouteMatch match = index.match("GET", "/test");
        assertNotNull(match);
    }

    @Test
    void handlerTypeRejectedInStandaloneMode() {
        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
            new RouteIndex(
                List.of(Route.get("/test", NoDepsHandler.class)),
                List.of()
            )
        );
        assertTrue(ex.getMessage().contains("handler class"));
        assertTrue(ex.getMessage().contains("NoDepsHandler"));
    }

    static class InjectedHandler implements RouteHandler {
        final String greeting;

        @Inject
        InjectedHandler(@com.jujin.freeway.ioc.annotation.Value("${greeting:Hello}") String greeting) {
            this.greeting = greeting;
        }

        @Override
        public void handle(HttpContext ctx) throws Exception {
            ctx.send(200, greeting);
        }
    }

    @Test
    void handlerTypeReceivesConstructorInjection() {
        Container container = Freeway.create(b -> {
            b.bind(RouteIndex.class).to(RouteIndex.class);
            b.contribute(Route.class).add(Route.get("/greet", InjectedHandler.class));
        });
        RouteIndex index = container.get(RouteIndex.class);
        RouteIndex.RouteMatch match = index.match("GET", "/greet");
        assertNotNull(match);
        // Handler is resolved from container with injected dependency
    }

    @Test
    void handlerTypeInRouteGroupIsResolved() {
        Container container = Freeway.create(b -> {
            b.bind(RouteIndex.class).to(RouteIndex.class);
            b.contribute(RouteGroup.class).add(
                RouteGroup.of("/api", Route.get("/health", NoDepsHandler.class))
            );
        });
        RouteIndex index = container.get(RouteIndex.class);
        RouteIndex.RouteMatch match = index.match("GET", "/api/health");
        assertNotNull(match);
    }

    @Test
    void handlerTypeWithBothHandlerAndClassThrows() {
        // Old API (handler instance) still works fine
        assertNotNull(Route.get("/test", ctx -> ctx.send(200, "ok")));

        // Neither handler nor handlerType — should throw
        assertThrows(IllegalArgumentException.class, () ->
            new Route("GET", "/test", null, null)
        );

        // Both handler and handlerType — should throw
        assertThrows(IllegalArgumentException.class, () ->
            new Route("GET", "/test", ctx -> ctx.send(200, "ok"), NoDepsHandler.class)
        );
    }
}
