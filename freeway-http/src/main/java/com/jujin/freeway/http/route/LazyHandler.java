package com.jujin.freeway.http.route;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.ioc.Container;

/**
 * A {@link RouteHandler} that wraps a handler class. The handler
 * instance is obtained from the container on the first invocation
 * and cached for subsequent calls.
 */
public final class LazyHandler implements RouteHandler {
    private final Class<? extends RouteHandler> handlerType;
    private volatile RouteHandler resolved;

    public LazyHandler(Class<? extends RouteHandler> handlerType) {
        this.handlerType = handlerType;
    }

    public RouteHandler resolve(Container container) {
        RouteHandler h = resolved;
        if (h == null) {
            synchronized (this) {
                h = resolved;
                if (h == null) {
                    resolved = h = container.get(handlerType);
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
