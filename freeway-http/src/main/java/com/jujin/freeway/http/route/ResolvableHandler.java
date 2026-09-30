package com.jujin.freeway.http.route;

import com.jujin.freeway.http.HttpContext;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A {@link RouteHandler} that stands for a handler <em>class</em> until the
 * container supplies an instance.
 *
 * <p>Two phases, and the split is the point. A route declared as
 * {@code Route.get("/path", MyHandler.class)} carries this wrapper, so the
 * contribution is a complete, statically visible class rather than a lambda
 * closure. {@code HttpModule} then calls {@link #resolve} while building the
 * route index, and the handler class is instantiated through
 * {@code Container.create} — constructor and {@code @Inject} field injection,
 * but <b>no {@code @PostConstruct}</b>.
 *
 * <p>That is deliberate. This handler is a singleton whose lifetime is the
 * server's, not a request's, and {@code Container.create} is the caller-owned
 * path: nothing walks the result at shutdown, so a {@code @PostConstruct} here
 * would have no {@code @PreDestroy} to pair with — a bean that opened a
 * connection in it would never release it. Lifecycle belongs to a binding; see
 * {@code Container.create}'s own javadoc.
 *
 * <p>Resolution is eager: {@code HttpModule} resolves every one of these
 * before the first request, so a misconfigured handler fails at startup rather
 * than on live traffic. The unresolved branches below are for an index built by
 * hand, without that step.
 *
 * <p>The route package never sees the container — the module keeps it and
 * supplies the built instance from the outside. This type is an implementation
 * detail of that arrangement, not API: it is {@code public} only so
 * {@code HttpModule} can reach it from another package.
 */
public final class ResolvableHandler implements RouteHandler {
    private final Class<? extends RouteHandler> handlerType;
    private volatile RouteHandler resolved;

    public ResolvableHandler(Class<? extends RouteHandler> handlerType) {
        this.handlerType = handlerType;
    }

    /** The handler class this route was declared with. */
    public Class<? extends RouteHandler> handlerType() {
        return handlerType;
    }

    /**
     * True once {@link #resolve} has handed in the built instance.
     *
     * <p>Consulted at index construction: a class-based route that reached the
     * index unresolved has no instance to dispatch to, and saying so while the
     * index is being assembled names the cause — the index was built without
     * {@code HttpModule} — instead of surfacing it on the first matching
     * request from inside the dispatch path. The unresolved branch in
     * {@link #handle} stays as a backstop for an index resolved afterwards.
     */
    public boolean isResolved() {
        return resolved != null;
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
        RouteHandler h = resolved;
        if (h == null) {
            // Reachable, and the message says why. HttpModule resolves every
            // class-based route while building the index, so this means the
            // index was built some other way — a hand-built RouteIndex, or a
            // binding of RouteIndex that skipped HttpModule. Both are
            // legitimate; neither resolves class routes on its own, so the
            // route is declared as a class and has nothing to dispatch to.
            throw new IllegalStateException(
                "Handler class " + handlerType.getName() + " was never resolved, "
                    + "so this route has no instance to dispatch to. HttpModule "
                    + "resolves class-based routes while building the index; a "
                    + "RouteIndex built another way must call "
                    + "ResolvableHandler.resolve(...) on each, or declare the handler "
                    + "as an instance — see Route.of(String, String, RouteHandler)");
        }
        h.handle(ctx);
    }
}
