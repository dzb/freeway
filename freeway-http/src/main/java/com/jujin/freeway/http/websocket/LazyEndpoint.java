package com.jujin.freeway.http.websocket;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A {@link WebSocketEndpoint} that wraps a handler class. The endpoint
 * instance is created through the container (constructor + field injection
 * + lifecycle) and handed in by {@code HttpModule} when the WebSocket index
 * is built at server startup — see {@code HttpModule}'s WebSocketIndex
 * binding — so missing or misconfigured endpoints fail fast at startup
 * rather than on the first upgrade. The websocket package itself never
 * sees the container: the module keeps it and supplies the built instance
 * from the outside.
 */
public final class LazyEndpoint implements WebSocketEndpoint {
    private final Class<? extends WebSocketEndpoint> endpointType;
    private volatile WebSocketEndpoint resolved;

    public LazyEndpoint(Class<? extends WebSocketEndpoint> endpointType) {
        this.endpointType = Objects.requireNonNull(endpointType, "endpointType");
    }

    /** The endpoint class this route was declared with. */
    public Class<? extends WebSocketEndpoint> endpointType() {
        return endpointType;
    }

    /**
     * Hands the built instance in; the first hand-in wins. The supplier is
     * consulted only while unresolved, so an endpoint shared with a group
     * expansion is instantiated exactly once.
     */
    public WebSocketEndpoint resolve(Supplier<WebSocketEndpoint> factory) {
        Objects.requireNonNull(factory, "factory");
        WebSocketEndpoint h = resolved;
        if (h == null) {
            synchronized (this) {
                h = resolved;
                if (h == null) {
                    resolved = h = Objects.requireNonNull(
                        factory.get(), "factory.get()"
                    );
                }
            }
        }
        return h;
    }

    @Override
    public WebSocketListener open(WebSocketSession session) throws Exception {
        WebSocketEndpoint h = resolved;
        if (h == null) {
            throw new IllegalStateException(
                "LazyEndpoint not resolved before upgrade: " + endpointType);
        }
        return h.open(session);
    }

    @Override
    public Set<String> subprotocols() {
        WebSocketEndpoint h = resolved;
        if (h == null) {
            throw new IllegalStateException(
                "LazyEndpoint not resolved before handshake: " + endpointType);
        }
        return h.subprotocols();
    }
}
