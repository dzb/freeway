package com.jujin.freeway.http.websocket;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.jujin.freeway.http.HttpModule;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.Freeway;
import com.jujin.freeway.ioc.annotation.Inject;
import com.jujin.freeway.ioc.annotation.Symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSocketIndexTest {
    @Test
    void literalRouteBeatsParameterRouteRegardlessOfRegistrationOrder() {
        WebSocketEndpoint parameter = session -> WebSocketListener.NOOP;
        WebSocketEndpoint literal = session -> WebSocketListener.NOOP;
        var index = new WebSocketIndex(List.of(
            WebSocketRoute.of("/ws/{id}", parameter),
            WebSocketRoute.of("/ws/admin", literal)), List.of());
        assertSame(literal, index.match("GET", "/ws/admin").endpoint());
    }

    @Test
    void rejectsAGroupRouteCollidingWithAnExplicitRoute() {
        // WebSocket routes follow the HTTP rule: a repeated method+path fails
        // instead of one declaration silently overriding the other.
        assertThrows(IllegalStateException.class, () -> new WebSocketIndex(
            List.of(WebSocketRoute.of("/ws/chat", session -> WebSocketListener.NOOP)),
            List.of(WebSocketGroup.of("/ws",
                WebSocketRoute.of("/chat", session -> WebSocketListener.NOOP)))));
    }

    // ──── endpoint class registration ────

    static final class GreetedEndpoint implements WebSocketEndpoint {
        final String greeting;

        @Inject
        GreetedEndpoint(@Symbol("${greeting:Hello}") String greeting) {
            this.greeting = greeting;
        }

        @Override
        public WebSocketListener open(WebSocketSession session) {
            return WebSocketListener.NOOP;
        }

        @Override
        public Set<String> subprotocols() {
            return Set.of("chat");
        }
    }

    @Test
    void classRouteMatchesWithResolutionDeferredInStandaloneIndex() {
        var index = new WebSocketIndex(List.of(
            WebSocketRoute.of("/cls", GreetedEndpoint.class)), List.of());
        var match = index.match("GET", "/cls");
        assertNotNull(match);
        assertTrue(match.endpoint() instanceof LazyEndpoint,
            "class route must register through the lazy wrapper");
        var ex = assertThrows(IllegalStateException.class,
            () -> match.endpoint().open(null));
        assertTrue(ex.getMessage().contains("GreetedEndpoint"),
            "unresolved use must fail loud and name the endpoint class");
    }

    @Test
    void classEndpointResolvesFromContainerWithInjection() {
        Container container = Freeway.create(
            new HttpModule(),
            b -> b.contribute(WebSocketRoute.class)
                .add(WebSocketRoute.of("/cls", GreetedEndpoint.class))
        );
        var match = container.get(WebSocketIndex.class).match("GET", "/cls");
        assertNotNull(match);
        var built = ((LazyEndpoint) match.endpoint()).resolve(() -> {
            throw new AssertionError(
                "resolved at startup — the supplier must never run");
        });
        assertTrue(built instanceof GreetedEndpoint);
        assertEquals("Hello", ((GreetedEndpoint) built).greeting,
            "constructor injection must reach the endpoint class");
        assertEquals(Set.of("chat"), match.endpoint().subprotocols(),
            "subprotocols must delegate to the resolved endpoint");
        container.close();
    }
}
