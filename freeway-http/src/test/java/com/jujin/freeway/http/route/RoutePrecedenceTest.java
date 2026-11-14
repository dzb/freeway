package com.jujin.freeway.http.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Routing precedence is a contract, not an accident of insertion order:
 * literal beats a parameter, a regex-constrained parameter beats a plain one,
 * a wildcard only runs when no more specific route matches, and HEAD falls
 * back to GET.
 */
class RoutePrecedenceTest {

    private static final RouteHandler LITERAL = ctx -> ctx.sendText(200, "literal");
    private static final RouteHandler PARAM = ctx -> ctx.sendText(200, "param");
    private static final RouteHandler REGEX = ctx -> ctx.sendText(200, "regex");
    private static final RouteHandler WILDCARD = ctx -> ctx.sendText(200, "wildcard");

    @Test
    void literalSegmentBeatsAPathParameter() {
        RouteIndex index = new RouteIndex(List.of(
            Route.get("/users/{id}", PARAM),
            Route.get("/users/me", LITERAL)));

        assertSame(LITERAL, index.match("GET", "/users/me").handler(),
            "a literal segment is more specific than a parameter");
        assertSame(PARAM, index.match("GET", "/users/42").handler());
    }

    @Test
    void regexConstrainedParameterBeatsAPlainOne() {
        RouteIndex index = new RouteIndex(List.of(
            Route.get("/items/{name}", PARAM),
            Route.get("/items/{id:\\d+}", REGEX)));

        assertSame(REGEX, index.match("GET", "/items/42").handler(),
            "a regex-constrained parameter is more specific than a plain one");
        assertSame(PARAM, index.match("GET", "/items/abc").handler(),
            "the plain parameter serves values the regex rejects");
    }

    @Test
    void wildcardOnlyServesPathsNoMoreSpecificRouteMatches() {
        RouteIndex index = new RouteIndex(List.of(
            Route.get("/files/{name}", PARAM),
            Route.get("/files/{path:.*}", WILDCARD)));

        assertSame(PARAM, index.match("GET", "/files/a").handler(),
            "a one-segment parameter is more specific than the wildcard");
        assertSame(WILDCARD, index.match("GET", "/files/a/b").handler(),
            "the wildcard consumes the remaining segments");
    }

    @Test
    void methodMatchPrecedesTheHeadFallback() {
        RouteIndex index = new RouteIndex(List.of(
            Route.get("/resource", LITERAL),
            Route.post("/resource", PARAM)));

        assertSame(PARAM, index.match("POST", "/resource").handler(),
            "the exact method wins over the GET fallback");
        assertSame(LITERAL, index.match("HEAD", "/resource").handler(),
            "HEAD falls back to GET when it has no route of its own");
        assertEquals(Set.of("GET", "HEAD", "POST"), index.methods("/resource"));
    }

    @Test
    void duplicateShapesAreRefusedNotOrderedAway() {
        assertTrue(assertThrows(IllegalStateException.class, () ->
                new RouteIndex(List.of(
                    Route.get("/users/{id}", PARAM),
                    Route.get("/users/{name}", LITERAL))))
            .getMessage().contains("same matching shape"),
            "parameter names do not create a second route shape");
    }
}
