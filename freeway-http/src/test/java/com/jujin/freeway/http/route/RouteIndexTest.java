package com.jujin.freeway.http.route;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.websocket.WebSocketListener;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.Symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void methodsListsTheMethodsThatRecognizeThePath() {
        RouteIndex registry = new RouteIndex(List.of(
            Route.get("/users", ctx -> ctx.send(200, "ok")),
            Route.post("/users", ctx -> ctx.send(201, "created")),
            Route.get("/users/{id}", ctx -> ctx.send(200, "ok"))), List.of());

        // HEAD rides along with GET — the same fallback match() applies.
        assertEquals(Set.of("GET", "HEAD", "POST"),
            registry.methods("/users"));
        assertEquals(Set.of("GET", "HEAD"),
            registry.methods("/users/42"));
        // Path no method recognizes: the caller answers 404, not 405.
        assertTrue(registry.methods("/absent").isEmpty());
        // Method-constrained routes only claim paths their constraint accepts.
        RouteIndex constrained = new RouteIndex(List.of(
            Route.get("/items/{id:\\d+}", ctx -> ctx.send(200, "number"))), List.of());
        assertTrue(constrained.methods("/items/42").contains("GET"));
        assertTrue(constrained.methods("/items/abc").isEmpty());
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
    void rejectsGroupRouteCollidingWithAnExplicitRoute() {
        // One rule for both declaration forms: a group route and an explicit
        // route with the same method+path collide loudly instead of one of
        // them silently winning.
        assertThrows(IllegalStateException.class, () -> new RouteIndex(
            List.of(Route.get("/api/users", ctx -> ctx.send(200, "explicit"))),
            List.of(RouteGroup.of("/api", Route.get("/users", ctx -> ctx.send(200, "group"))))
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
    void encodedLiteralRegistrationMatchesPlainAndEncodedRequests() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/hello%20world", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        assertNotNull(registry.match("GET", "/hello world"),
            "an encoded registration must match the plain (decoded) request");
        assertNotNull(registry.match("GET", "/hello%20world"),
            "an encoded registration must match the same encoded request");
    }

    @Test
    void encodedSlashRegistrationStaysInsideOneSegment() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/files/a%2Fb", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        assertNotNull(registry.match("GET", "/files/a%2Fb"),
            "an encoded slash must stay inside its original segment");
        assertNull(registry.match("GET", "/files/a/b"),
            "an encoded slash must not be treated as a path separator");
    }

    @Test
    void rejectsMalformedPercentEncodingAtRegistration() {
        assertThrows(IllegalArgumentException.class, () ->
            Route.get("/files/%zz", ctx -> ctx.send(200, "ok")));
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

    @Test
    void overlongRegexConstrainedSegmentsAreRejectedBeforeRegexMatching() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/id/{id:(a+)+$}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        // A 4KB+ 'a' segment would drive catastrophic backtracking on
        // (a+)+$ without the pre-regex segment length cap; it must be
        // rejected outright, fast, and without throwing.
        long start = System.nanoTime();
        assertNull(registry.match("GET", "/id/" + "a".repeat(4096)),
            "an overlong segment must not match");
        assertNull(registry.match("GET", "/id/" + "a".repeat(4096) + "!"),
            "an overlong near-miss segment must not reach the regex");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMs < 2000,
            "overlong segments must be rejected quickly (took " + elapsedMs + "ms)");
        // Normal segments still match the same constraint.
        RouteIndex.RouteMatch match = registry.match("GET", "/id/aaa");
        assertNotNull(match);
        assertEquals("aaa", match.pathVariables().get("id"));
    }

    @Test
    void regexConstrainedSegmentsAtTheLengthCapStillMatch() {
        RouteIndex registry = new RouteIndex(
            List.of(Route.get("/id/{id:(a+)+$}", ctx -> ctx.send(200, "ok"))),
            List.of()
        );
        String atCap = "a".repeat(PathPattern.MAX_SEGMENT_LENGTH);
        RouteIndex.RouteMatch match = registry.match("GET", "/id/" + atCap);
        assertNotNull(match);
        assertEquals(atCap, match.pathVariables().get("id"));
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
        InjectedHandler(@Symbol("${greeting:Hello}") String greeting) {
            this.greeting = greeting;
        }
        @Override public void handle(HttpContext ctx) throws Exception {
            ctx.send(200, greeting);
        }
    }

    /**
     * A direct binding of {@code RouteIndex} — the application owns the index
     * rather than letting HttpModule build it — still has to turn a handler
     * class into an instance, because only the caller holding a container can
     * do that. Resolving explicitly is the supported standalone shape.
     */
    @Test
    void handlerClassResolvedByTheCallerThatOwnsTheIndex() {
        Container container = Freeway.create(b ->
            b.bind(RouteIndex.class).to(c -> {
                Route r = Route.get("/test", NoDepsHandler.class);
                ResolvableHandler h = (ResolvableHandler) r.handler();
                h.resolve(() -> c.create(h.handlerType()));
                return new RouteIndex(List.of(r), List.of());
            }));
        assertNotNull(container.get(RouteIndex.class).match("GET", "/test"));
    }

    /**
     * A class route that reached the index unresolved is refused at assembly.
     *
     * <p>This is the check the old "Lazy" name had been discouraging: the
     * WebSocketIndex applied it to endpoints, RouteIndex did not, and the
     * failure surfaced on the first matching request from inside dispatch,
     * naming only a class. Both indexes now refuse the same state, at the same
     * point, with the same diagnosis.
     */
    @Test
    void unresolvedHandlerClassIsRefusedAtAssembly() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new RouteIndex(List.of(Route.get("/test", NoDepsHandler.class)), List.of()));
        assertTrue(ex.getMessage().contains(NoDepsHandler.class.getName()),
            "must name the class that has no instance: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("HttpModule"),
            "must name who normally resolves it: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("/test"),
            "must name the offending route: " + ex.getMessage());
    }

    @Test
    void handlerClassReceivesConstructorInjection() {
        // The symbol is resolved by the container, so this asserts the whole
        // point of resolving through it: the handler the index dispatches to
        // is the one the container built, dependencies included.
        Container container = Freeway.create(b -> {
            b.bind(RouteIndex.class).to(c -> {
                Route r = Route.get("/greet", InjectedHandler.class);
                ResolvableHandler h = (ResolvableHandler) r.handler();
                h.resolve(() -> c.create(h.handlerType()));
                return new RouteIndex(List.of(r), List.of());
            });
        });
        RouteIndex index = container.get(RouteIndex.class);
        var match = index.match("GET", "/greet");
        assertNotNull(match);
        // The index holds the wrapper; resolve() is idempotent and hands back
        // the instance the container built — without consulting the factory
        // again, which is what makes one handler class become one singleton
        // however many routes reference it.
        RouteHandler handler = ((ResolvableHandler) match.handler())
            .resolve(() -> { throw new IllegalStateException("supplier must not re-run"); });
        assertInstanceOf(InjectedHandler.class, handler,
            "the index must dispatch to the instance the container built");
        assertEquals("Hello", ((InjectedHandler) handler).greeting,
            "and that instance carries its injected dependency");
    }

    @Test
    void handlerClassInRouteGroupIsResolved() {
        // The group path is the one HttpModule handles in two passes — expand,
        // then resolve what it produced — so a caller doing it by hand must do
        // the same, and both must happen on the same expansion: expand()
        // builds fresh Route objects, so resolving one expansion and indexing
        // another would leave the index holding an unresolved route.
        RouteGroup group = RouteGroup.of("/api", Route.get("/health", NoDepsHandler.class));

        assertThrows(IllegalStateException.class,
            () -> new RouteIndex(List.of(), List.of(group)),
            "an unresolved group-expanded class route is refused the same way");

        Container container = Freeway.create(b ->
            b.bind(RouteIndex.class).to(c -> {
                List<Route> expanded = group.expand();
                for (Route r : expanded) {
                    if (r.handler() instanceof ResolvableHandler h) {
                        h.resolve(() -> c.create(h.handlerType()));
                    }
                }
                return new RouteIndex(expanded, List.of());
            }));
        assertNotNull(container.get(RouteIndex.class).match("GET", "/api/health"),
            "a group-expanded class route is resolved the same way as a direct one");
    }
}
