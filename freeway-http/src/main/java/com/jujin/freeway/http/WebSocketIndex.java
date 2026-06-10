package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Extension;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

final class WebSocketIndex {
    private final CopyOnWriteArrayList<WebSocketRoute> routes = new CopyOnWriteArrayList<>();

    public WebSocketIndex(
        Extension<WebSocketRoute> routes,
        Extension<WebSocketGroup> groups
    ) {
        // groups first, then individuals: groups define base routing, individuals override
        for (WebSocketGroup group : groups.all()) {
            for (WebSocketRoute route : group.expand()) {
                add(route);
            }
        }
        for (WebSocketRoute route : routes.all()) {
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
        WebSocketRoute value = Objects.requireNonNull(route, "route");
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
