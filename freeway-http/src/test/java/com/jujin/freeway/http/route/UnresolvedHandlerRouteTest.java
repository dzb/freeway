package com.jujin.freeway.http.route;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpContext;

/**
 * A class-based route in an index that {@code HttpModule} did not build is
 * refused at assembly, with a message that carries the diagnosis.
 *
 * <p>Building a {@code RouteIndex} by hand is a supported route — the
 * constructor takes plain lists and an application may bind it directly. What
 * it cannot do on its own is turn a handler <em>class</em> into an instance:
 * {@code HttpModule} is what calls {@link ResolvableHandler#resolve}, because
 * it is what holds the container.
 *
 * <p>So the failure is reachable, and the check belongs at assembly: that is
 * the point where the whole route set is in hand and the cause is nameable.
 * Before the check, it surfaced on the first matching request from inside
 * dispatch, reporting only a class name — which suggests nothing about why
 * there is no instance. {@code WebSocketIndex} has applied this rule to
 * endpoints all along; the two indexes now agree.
 *
 * <p>{@code RouteIndexTest} covers that the check fires. This class is about
 * the message, since a refusal a reader cannot act on is only marginally
 * better than no refusal.
 */
class UnresolvedHandlerRouteTest {

    public static final class Plain implements RouteHandler {
        @Override
        public void handle(HttpContext ctx) throws java.io.IOException {
            ctx.send(200, "ok");
        }
    }

    @Test
    void theRefusalSaysWhichRouteWhichClassAndWhatToDo() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new RouteIndex(List.of(Route.get("/orders/{id}", Plain.class)), List.of()));

        String message = ex.getMessage();
        assertTrue(message.contains("/orders/{id}"),
            "must name the offending route, so it is findable in a large set: " + message);
        assertTrue(message.contains(Plain.class.getName()),
            "must name the class that has no instance: " + message);
        assertTrue(message.contains("HttpModule"),
            "must name who normally resolves it: " + message);
        assertTrue(message.contains("handler instance"),
            "must offer the way out — declare the route with an instance: " + message);
    }

    @Test
    void callingAnUnresolvedWrapperDirectlySaysWhatToDo() {
        // An index cannot hold this wrapper unresolved, so the only way to
        // reach the guard is to keep the wrapper and call it — which is worth
        // naming rather than letting dispatch fail on a null instance.
        ResolvableHandler wrapper = new ResolvableHandler(Plain.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> wrapper.handle(null));

        String message = ex.getMessage();
        assertTrue(message.contains(Plain.class.getName()),
            "must name the class that has no instance: " + message);
        assertTrue(message.contains("invoked directly"),
            "must say how an unresolved wrapper got here: " + message);
        assertTrue(message.contains("ResolvableHandler.resolve"),
            "must name the way out: " + message);
    }

    @Test
    void resolvingFirstThenIndexingWorks() {
        // The supported standalone shape: declare, resolve, then index — all on
        // the same Route, since resolve records on the wrapper the route holds.
        Route route = Route.get("/test", Plain.class);
        ((ResolvableHandler) route.handler()).resolve(Plain::new);

        RouteIndex index = new RouteIndex(List.of(route), List.of());
        var match = index.match("GET", "/test");
        assertNotNull(match, "a resolved class route indexes and matches like any other");
        assertTrue(match.handler() instanceof ResolvableHandler,
            "the wrapper stays in place; it is what holds the instance");
    }

    @Test
    void theFactoryIsConsultedOnlyOnce() {
        Route route = Route.get("/test", Plain.class);
        ResolvableHandler h = (ResolvableHandler) route.handler();

        int[] built = {0};
        h.resolve(() -> { built[0]++; return new Plain(); });
        h.resolve(() -> { built[0]++; return new Plain(); });

        assertTrue(built[0] == 1,
            "a handler shared across routes, or resolved twice, must be "
                + "instantiated once — it is a singleton for the server's life");
    }

    @Test
    void anInstanceRouteIsUnaffected() {
        // The other declaration form never needed resolving and must not be
        // dragged into the check.
        RouteIndex index = new RouteIndex(
            List.of(Route.get("/test", ctx -> ctx.send(200, "ok"))), List.of());
        assertNotNull(index.match("GET", "/test"));
    }
}
