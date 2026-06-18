package com.jujin.freeway.http.websocket;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class WebSocketIndex {

    private final CopyOnWriteArrayList<WebSocketRoute> routes =
        new CopyOnWriteArrayList<>();

    public WebSocketIndex(
        List<WebSocketRoute> routes,
        List<WebSocketGroup> groups
    ) {
        // groups first, then individuals: groups define base routing, individuals override
        for (WebSocketGroup group : groups) {
            for (WebSocketRoute route : group.expand()) {
                add(route);
            }
        }
        for (WebSocketRoute route : routes) {
            add(route);
        }
    }

    public WebSocketMatch match(String method, String path) {
        if (!"GET".equalsIgnoreCase(method)) {
            return null;
        }
        // reverse iteration: individuals (added last) override groups (added first)
        for (int i = routes.size() - 1; i >= 0; i--) {
            WebSocketRoute route = routes.get(i);
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
            for (int i = 0; i < routes.size(); i++) {
                if (routes.get(i).path().equals(value.path())) {
                    routes.set(i, value); // individuals override groups
                    return;
                }
            }
            routes.add(value);
        }
    }
}
