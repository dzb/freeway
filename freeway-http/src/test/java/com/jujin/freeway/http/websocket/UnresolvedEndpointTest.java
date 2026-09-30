package com.jujin.freeway.http.websocket;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The unresolved-endpoint guard names the class, the phase and the way out.
 *
 * <p>{@code WebSocketIndex} refuses to build while an endpoint is unresolved —
 * the same rule {@code RouteIndex} applies to class routes — so this guard is
 * reachable only for a wrapper invoked directly, and it has to carry the whole
 * explanation because nothing else will. It is the endpoint-side counterpart of
 * {@code ResolvableHandler.handle}'s guard, and both messages say the same
 * things in the same order.
 */
class UnresolvedEndpointTest {

    static final class Endpoint implements WebSocketEndpoint {
        @Override
        public WebSocketListener open(WebSocketSession session) {
            throw new AssertionError("an unresolved wrapper must not reach the endpoint");
        }

        @Override
        public java.util.Set<String> subprotocols() {
            return java.util.Set.of();
        }
    }

    @Test
    void openOnAnUnresolvedWrapperNamesTheClassAndTheFix() {
        var wrapper = new ResolvableEndpoint(Endpoint.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> wrapper.open(null));

        String message = ex.getMessage();
        assertTrue(message.contains(Endpoint.class.getName()),
            "must print the class name, not " + Endpoint.class + ": " + message);
        assertTrue(message.contains("upgrade"),
            "must say which phase called it: " + message);
        assertTrue(message.contains("ResolvableEndpoint.resolve"),
            "must name the way out: " + message);
    }

    @Test
    void subprotocolsOnAnUnresolvedWrapperUsesTheSameGuard() {
        var wrapper = new ResolvableEndpoint(Endpoint.class);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            wrapper::subprotocols);

        String message = ex.getMessage();
        assertTrue(message.contains("handshake"), message);
        assertTrue(message.contains(Endpoint.class.getName()), message);
        assertTrue(message.contains("invoked directly"), message);
    }
}
