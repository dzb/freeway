package com.jujin.freeway2.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record RouteGroup(String prefix, List<Route> routes) {
    public RouteGroup {
        prefix = PathGroupSupport.normalizePrefix(prefix);
        routes = List.copyOf(routes);
    }

    public static RouteGroup of(String prefix, Route... routes) {
        List<Route> items = new ArrayList<>(routes == null ? 0 : routes.length);
        if (routes != null) {
            for (Route route : routes) {
                items.add(Objects.requireNonNull(route, "route"));
            }
        }
        return new RouteGroup(prefix, items);
    }

    public List<Route> expand() {
        List<Route> expanded = new ArrayList<>(routes.size());
        for (Route route : routes) {
            expanded.add(Route.of(route.method(), PathGroupSupport.join(prefix, route.path()), route.handler()));
        }
        return List.copyOf(expanded);
    }
}
