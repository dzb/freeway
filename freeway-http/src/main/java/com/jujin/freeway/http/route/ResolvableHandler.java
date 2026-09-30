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
 * than on live traffic. {@link RouteIndex} refuses to be built while one is
 * unresolved, so the guard in {@link #handle} is not an index path: it is for a
 * wrapper invoked directly, by a caller holding it without an index.
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
     * {@link #handle} is the separate guard for a wrapper the caller invoked
     * directly; an index never holds one.
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
            // An unresolved wrapper cannot be inside an index — RouteIndex
            // refuses to build one — so this means the wrapper itself was
            // called. It still names the class and the way out rather than
            // failing on a null dispatch.
            throw new IllegalStateException(
                "Handler class " + handlerType.getName() + " was never resolved: this "
                    + "route wrapper was invoked directly, outside an index. HttpModule "
                    + "resolves class routes while building the index, and RouteIndex "
                    + "refuses to build one that still holds an unresolved wrapper — so "
                    + "call ResolvableHandler.resolve(factory) on the wrapper you hold, or "
                    + "declare the handler as an instance "
                    + "(Route.of(String, String, RouteHandler))");
        }
        h.handle(ctx);
    }
}
