package com.jujin.freeway.http.websocket;

import com.jujin.freeway.http.HttpContext;
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
     * Contribution-consumed routes: both parameter lists are resolved from
     * {@code binder.contribute(...)} extensions when the container builds
     * this class (see {@code HttpModule}) — constructor parameters consume
     * contributions implicitly.
     */
    public WebSocketIndex(
        List<WebSocketRoute> routes,
        List<WebSocketGroup> groups
    ) {
        List<Route> adapted = new ArrayList<>();
        if (groups != null) {
            for (WebSocketGroup group : groups) {
                for (WebSocketRoute route : group.expand()) {
                    adapted.add(adapt(route));
                }
            }
        }
        if (routes != null) {
            for (WebSocketRoute route : routes) {
                adapted.add(adapt(route));
            }
        }
        this.routes = new RouteIndex(adapted, List.of());
    }

    private static Route adapt(WebSocketRoute route) {
        return Route.get(route.path(), new EndpointHandler(route.endpoint()));
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
        return new WebSocketMatch(handler.endpoint(), match.pathVariables());
    }

    /** RouteHandler adapter so WebSocket endpoints can live in the shared
     *  trie; never dispatched over HTTP. */
    private record EndpointHandler(WebSocketEndpoint endpoint)
            implements RouteHandler {
        @Override
        public void handle(HttpContext ctx) {
            throw new UnsupportedOperationException(
                "WebSocket endpoint handlers are never dispatched over HTTP");
        }
    }
}
