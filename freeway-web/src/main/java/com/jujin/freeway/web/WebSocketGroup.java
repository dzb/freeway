package com.jujin.freeway.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record WebSocketGroup(String prefix, List<WebSocketRoute> routes) {
    public WebSocketGroup {
        prefix = PathGroupSupport.normalizePrefix(prefix);
        routes = List.copyOf(routes);
    }

    public static WebSocketGroup of(String prefix, WebSocketRoute... routes) {
        List<WebSocketRoute> items = new ArrayList<>(routes == null ? 0 : routes.length);
        if (routes != null) {
            for (WebSocketRoute route : routes) {
                items.add(Objects.requireNonNull(route, "route"));
            }
        }
        return new WebSocketGroup(prefix, items);
    }

    public List<WebSocketRoute> expand() {
        List<WebSocketRoute> expanded = new ArrayList<>(routes.size());
        for (WebSocketRoute route : routes) {
            expanded.add(WebSocketRoute.of(PathGroupSupport.join(prefix, route.path()), route.endpoint()));
        }
        return List.copyOf(expanded);
    }
}
