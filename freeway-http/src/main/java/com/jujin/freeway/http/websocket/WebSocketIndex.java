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
        WebSocketMatch best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = routes.size() - 1; i >= 0; i--) {
            WebSocketRoute route = routes.get(i);
            Map<String, String> vars = route.pattern().match(path);
            if (vars != null) {
                int score = specificity(route.path());
                if (best == null || score > bestScore) {
                    best = new WebSocketMatch(route.endpoint(), vars);
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private static int specificity(String path) {
        int score = 0;
        for (String segment : path.split("/")) {
            if (segment.startsWith("{") && segment.endsWith("}")) {
                score += segment.contains(":") ? 20 : 10;
                if (segment.endsWith(":.*}")) score -= 10;
            } else if (segment.startsWith(":")) {
                score += 10;
            } else {
                score += 30;
            }
        }
        return score;
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
