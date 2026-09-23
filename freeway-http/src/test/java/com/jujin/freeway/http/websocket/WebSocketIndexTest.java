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
    void standaloneIndexRejectsUnresolvedClassRouteAtAssembly() {
        // HttpModule resolves every class endpoint before building the index;
        // a by-hand index has no such step, so it must fail here — at
        // assembly, naming the class — instead of deferring to the first
        // upgrade where it used to be swallowed at TRACE.
        var ex = assertThrows(IllegalStateException.class, () -> new WebSocketIndex(
            List.of(WebSocketRoute.of("/cls", GreetedEndpoint.class)), List.of()));
        assertTrue(ex.getMessage().contains("GreetedEndpoint"),
            "assembly failure must name the endpoint class: " + ex.getMessage());
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

    /** Endpoint whose constructor dependency nobody binds — startup must fail. */
    static final class NeedyEndpoint implements WebSocketEndpoint {
        interface Unbound {}

        @Inject
        NeedyEndpoint(Unbound dep) {
        }

        @Override
        public WebSocketListener open(WebSocketSession session) {
            return WebSocketListener.NOOP;
        }
    }

    @Test
    void endpointWithUnsatisfiableConstructorFailsWhenTheIndexIsBuilt() {
        // Resolution happens inside the WebSocketIndex binding, so the
        // unsatisfiable constructor surfaces at container.get(...), not on
        // the first upgrade — and the cause chain names the endpoint.
        Container container = Freeway.create(
            new HttpModule(),
            b -> b.contribute(WebSocketRoute.class)
                .add(WebSocketRoute.of("/needy", NeedyEndpoint.class))
        );
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> container.get(WebSocketIndex.class));
        StringBuilder chain = new StringBuilder();
        for (Throwable t = ex; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        assertTrue(chain.toString().contains("NeedyEndpoint"),
            "startup failure must name the endpoint class:\n" + chain);
        container.close();
    }
}
