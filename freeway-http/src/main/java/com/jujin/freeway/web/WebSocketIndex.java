package com.jujin.freeway.web;

import com.jujin.freeway.ioc.annotation.ExtensionPoint;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

final class WebSocketIndex {
    private final CopyOnWriteArrayList<WebSocketRoute> routes = new CopyOnWriteArrayList<>();

    public WebSocketIndex(
        @ExtensionPoint(WebSocketRoute.class) Collection<WebSocketRoute> routes,
        @ExtensionPoint(WebSocketGroup.class) Collection<WebSocketGroup> groups
    ) {
        // groups first, then individuals: groups define base routing, individuals override
        for (WebSocketGroup group : groups == null ? List.<WebSocketGroup>of() : groups) {
            for (WebSocketRoute route : group.expand()) {
                add(route);
            }
        }
        for (WebSocketRoute route : routes == null ? List.<WebSocketRoute>of() : routes) {
            add(route);
        }
    }

    public WebSocketMatch match(String method, String path) {
        if (!"GET".equalsIgnoreCase(method)) {
            return null;
        }
        for (WebSocketRoute route : routes) {
            Map<String, String> vars = route.pattern().match(path);
            if (vars != null) {
                return new WebSocketMatch(route.endpoint(), vars);
            }
        }
        return null;
    }

    private void add(WebSocketRoute route) {
        WebSocketRoute value = java.util.Objects.requireNonNull(route, "route");
        synchronized (routes) {
            for (WebSocketRoute existing : routes) {
                if (existing.path().equals(value.path())) {
                    throw new IllegalStateException("Duplicate websocket route detected: GET " + value.path());
                }
            }
            routes.add(value);
        }
    }
}
