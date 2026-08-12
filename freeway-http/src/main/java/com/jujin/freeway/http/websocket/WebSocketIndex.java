package com.jujin.freeway.http.websocket;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.http.route.RouteIndex;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WebSocket route index backed by the same {@link RouteIndex} trie as HTTP
 * routes, so both route families share one matching implementation (literal
 * segments, path parameters, regex constraints, wildcards) and one
 * specificity rule. Groups are registered first; individual routes with the
 * same path override group-expanded routes.
 */
public final class WebSocketIndex {

    private final RouteIndex routes;

    public WebSocketIndex(
        List<WebSocketRoute> routes,
        List<WebSocketGroup> groups
    ) {
        // Groups first, then individuals: individuals override by exact path.
        Map<String, WebSocketRoute> byPath = new LinkedHashMap<>();
        for (WebSocketGroup group : groups) {
            for (WebSocketRoute route : group.expand()) {
                byPath.put(route.path(), route);
            }
        }
        for (WebSocketRoute route : routes) {
            byPath.put(route.path(), route);
        }
        List<Route> adapted = byPath.values().stream()
            .map(r -> Route.get(r.path(), new EndpointHandler(r.endpoint())))
            .toList();
        this.routes = new RouteIndex(adapted, List.of());
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
