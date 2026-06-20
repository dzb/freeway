package com.jujin.freeway.http.route;

import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class RouteIndexBenchmark {

    private RouteIndex exactIndex;
    private RouteIndex paramIndex;
    private RouteIndex wildcardIndex;

    @Setup
    public void setup() {
        exactIndex = new RouteIndex(routes("/ping", "/health", "/metrics"), List.of());
        paramIndex = new RouteIndex(
            routes("/users/{id}", "/users/{id}/posts", "/orders/{orderId}"),
            List.of()
        );
        wildcardIndex = new RouteIndex(
            routes("/assets/{path:.*}", "/static/{path:.*}", "/downloads/{path:.*}"),
            List.of()
        );
    }

    @Benchmark
    public RouteIndex.RouteMatch exactMatch() {
        return exactIndex.match("GET", "/ping");
    }

    @Benchmark
    public RouteIndex.RouteMatch paramMatch() {
        return paramIndex.match("GET", "/users/42");
    }

    @Benchmark
    public RouteIndex.RouteMatch wildcardMatch() {
        return wildcardIndex.match("GET", "/assets/css/app.css");
    }

    private static List<Route> routes(String... paths) {
        List<Route> routes = new ArrayList<>(paths.length);
        for (String path : paths) {
            routes.add(Route.get(path, ctx -> {}));
        }
        return routes;
    }
}
