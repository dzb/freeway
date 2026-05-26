package com.jujin.freeway.web;

import com.jujin.freeway.ioc.annotation.ExtensionPoint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


final class RouteIndex {
    private final ConcurrentHashMap<String, List<RouteEntry>> routes = new ConcurrentHashMap<>();

    public RouteIndex(
        @ExtensionPoint(Route.class) Collection<Route> routes,
        @ExtensionPoint(RouteGroup.class) Collection<RouteGroup> groups
    ) {
        for (Route route : routes == null ? List.<Route>of() : routes) {
            addRoute(route.method(), route.path(), route.handler());
        }
        for (RouteGroup group : groups == null ? List.<RouteGroup>of() : groups) {
            for (Route route : group.expand()) {
                addRoute(route.method(), route.path(), route.handler());
            }
        }
        // freeze all lists to allow lock-free concurrent reads
        this.routes.replaceAll((k, v) -> List.copyOf(v));
    }

    public int routeCount() {
        return routes.values().stream().mapToInt(List::size).sum();
    }

    private void addRoute(String method, String path, RouteHandler handler) {
        String key = method == null ? "" : method.toUpperCase();
        List<RouteEntry> list = routes.computeIfAbsent(key, ignored -> new ArrayList<>());
        synchronized (list) {
            for (RouteEntry existing : list) {
                if (existing.path().equals(path)) {
                    throw new IllegalStateException("Duplicate route detected: " + key + " " + path);
                }
            }
            list.add(new RouteEntry(key, path, handler));
        }
    }

    public RouteMatch match(String method, String path) {
        String key = method == null ? "" : method.toUpperCase();
        List<RouteEntry> candidates = routes.get(key);
        RouteMatch result = matchEntry(candidates, path);
        if (result != null || !"HEAD".equals(key)) {
            return result;
        }
        return matchEntry(routes.get("GET"), path);
    }

    private RouteMatch matchEntry(List<RouteEntry> candidates, String path) {
        if (candidates == null) {
            return null;
        }
        for (RouteEntry route : candidates) {
            Map<String, String> vars = route.match(path);
            if (vars != null) {
                return new RouteMatch(route.handler(), vars);
            }
        }
        return null;
    }

    private static final class RouteEntry {
        private final String method;
        private final String path;
        private final RouteHandler handler;
        private final PathPattern pattern;

        private RouteEntry(String method, String path, RouteHandler handler) {
            this.method = method == null ? "" : method.toUpperCase();
            this.path = path;
            this.handler = handler;
            this.pattern = new PathPattern(path);
        }

        String path() {
            return path;
        }

        RouteHandler handler() {
            return handler;
        }

        Map<String, String> match(String requestPath) {
            return pattern.match(requestPath);
        }
    }

    public record RouteMatch(RouteHandler handler, Map<String, String> pathVariables) {}
}
