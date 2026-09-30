package com.jujin.freeway.http.websocket;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A {@link WebSocketEndpoint} that stands for an endpoint <em>class</em> until
 * the container supplies an instance.
 *
 * <p>Same two-phase arrangement as {@code ResolvableHandler}, and for the same
 * reasons: a route declared as {@code WebSocketRoute.of(path, MyEndpoint.class)}
 * contributes a complete class, {@code HttpModule} instantiates it through
 * {@code Container.create} while building the WebSocket index, and the result
 * is <b>not</b> {@code @PostConstruct}-ed. This endpoint is a singleton — the
 * same instance serves every connection that upgrades on its route, since
 * {@code WebSocketIndex.match} hands the endpoint itself to
 * {@code WebSocketUpgrade} — so lifecycle belongs with a binding, and nothing
 * walking the result at shutdown could pair a {@code @PreDestroy} with
 * anything.
 *
 * <p>Per-connection state belongs on the {@code WebSocketListener} that
 * {@code open} returns, not on the endpoint.
 *
 * <p>Not API: {@code public} only so {@code HttpModule} can resolve it from
 * another package.
 */
public final class ResolvableEndpoint implements WebSocketEndpoint {
    private final Class<? extends WebSocketEndpoint> endpointType;
    private volatile WebSocketEndpoint resolved;

    public ResolvableEndpoint(Class<? extends WebSocketEndpoint> endpointType) {
        this.endpointType = Objects.requireNonNull(endpointType, "endpointType");
    }

    /** The endpoint class this route was declared with. */
    public Class<? extends WebSocketEndpoint> endpointType() {
        return endpointType;
    }

    /** True once {@link #resolve} has handed in the built instance. */
    public boolean isResolved() {
        return resolved != null;
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
                "ResolvableEndpoint not resolved before upgrade: " + endpointType);
        }
        return h.open(session);
    }

    @Override
    public Set<String> subprotocols() {
        WebSocketEndpoint h = resolved;
        if (h == null) {
            throw new IllegalStateException(
                "ResolvableEndpoint not resolved before handshake: " + endpointType);
        }
        return h.subprotocols();
    }
}
