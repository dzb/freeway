package com.jujin.freeway.http.websocket;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
