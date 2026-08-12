package com.jujin.freeway.http.websocket;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;

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
}
