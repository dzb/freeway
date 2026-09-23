package com.jujin.freeway.http.route;

import com.jujin.freeway.http.HttpContext;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link RouteHandler} that wraps a handler class. The handler instance is
 * created through the container — constructor injection, field injection and
 * {@code @PostConstruct}; the container does not track it afterwards, so
 * there is no {@code @PreDestroy} — and handed in by {@code HttpModule} when
 * the route index is built at server startup — see {@code HttpModule}'s
 * RouteIndex binding — so missing or misconfigured handlers fail fast at
 * startup rather than on the first request. The route package itself never
 * sees the container: the module keeps it and supplies the built instance
 * from the outside.
 */
public final class LazyHandler implements RouteHandler {
    private final Class<? extends RouteHandler> handlerType;
    private volatile RouteHandler resolved;

    public LazyHandler(Class<? extends RouteHandler> handlerType) {
        this.handlerType = handlerType;
    }

    /** The handler class this route was declared with. */
    public Class<? extends RouteHandler> handlerType() {
        return handlerType;
    }

    /**
     * Hands the built instance in; the first hand-in wins. The supplier is
     * consulted only while unresolved, so a handler shared by an expanded
     * route set is instantiated exactly once.
     */
    public RouteHandler resolve(Supplier<RouteHandler> factory) {
        Objects.requireNonNull(factory, "factory");
        RouteHandler h = resolved;
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
    public void handle(HttpContext ctx) throws Exception {
        if (resolved == null) {
            throw new IllegalStateException(
                "LazyHandler not resolved before request: " + handlerType);
        }
        resolved.handle(ctx);
    }
}
