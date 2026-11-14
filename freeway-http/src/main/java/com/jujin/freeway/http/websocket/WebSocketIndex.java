package com.jujin.freeway.http.websocket;

import com.jujin.freeway.http.HttpExchange;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.http.route.RouteIndex;

import java.util.ArrayList;
import java.util.List;

/**
 * WebSocket route index backed by the same {@link RouteIndex} trie as HTTP
 * routes, so both route families share one matching implementation (literal
 * segments, path parameters, regex constraints, wildcards), one specificity
 * rule and one registration rule: a repeated method+path fails startup,
 * whether it comes from a group, from an explicit route, or from both.
 * Nothing overrides silently.
 */
public final class WebSocketIndex {

    private final RouteIndex routes;

    /**
     * Endpoints ready for matching: callers expand groups and resolve
     * class-based endpoints before construction, so this index owns no
     * contribution or container vocabulary. {@code HttpModule} is that
     * assembly point; a one-off failure here means the index was built without
     * it and fails at assembly rather than on the first upgrade.
     */
    public WebSocketIndex(List<WebSocketRoute> routes) {
        List<Route> adapted = new ArrayList<>();
        if (routes != null) {
            for (WebSocketRoute route : routes) {
                adapted.add(adapt(route));
            }
        }
        this.routes = new RouteIndex(adapted);
    }

    private static Route adapt(WebSocketRoute route) {
        if (route.endpoint() instanceof ResolvableEndpoint le && !le.isResolved()) {
            throw new IllegalStateException(
                "WebSocket route " + route.path() + " uses endpoint class "
                    + le.endpointType().getName() + " but it was never "
                    + "resolved — HttpModule resolves class endpoints before "
                    + "building this index; build the index through the "
                    + "container, or declare an endpoint instance");
        }
        return Route.get(route.path(), new EndpointHandler(route.endpoint()));
    }

    /** Registered WebSocket paths in declaration order, without endpoints. */
    public List<String> paths() {
        return routes.descriptions().stream()
            .map(RouteIndex.RouteDescription::path)
            .toList();
    }

    public WebSocketMatch match(String method, String path) {
        if (!"GET".equalsIgnoreCase(method)) {
            return null;
        }
        RouteIndex.RouteMatch match = routes.match("GET", path);
        if (match == null) {
            return null;
        }
        EndpointHandler handler = (EndpointHandler) match.handler();
        return new WebSocketMatch(handler.endpoint(), match.pathVariables(), match.routePattern());
    }

    /** RouteHandler adapter so WebSocket endpoints can live in the shared
     *  trie; never dispatched over HTTP. */
    private record EndpointHandler(WebSocketEndpoint endpoint)
            implements RouteHandler {
        @Override
        public void handle(HttpExchange ctx) {
            throw new UnsupportedOperationException(
                "WebSocket endpoint handlers are never dispatched over HTTP");
        }
    }
}
