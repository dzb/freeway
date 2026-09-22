package com.jujin.freeway.http;

import java.util.ArrayList;
import java.util.List;

import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ErrorHandler;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketRoute;

/**
 * The request-handling parts a {@link WebServer} is built from — the single
 * parts list for both ways of assembling a server: {@link HttpModule} fills it
 * from the container, and a caller without a container fills it by hand (or
 * starts from {@link #of(Route...)}, which is the whole story for a server with
 * nothing but routes).
 *
 * <p>It carries parts, not policy: the built-in error mapper is appended and the
 * inactive filters skipped by {@link WebServer#create}, so neither rule can be
 * stated differently on the two paths.
 */
public record RequestComponents(
    RouteIndex routes,
    WebSocketIndex websocketIndex,
    CorsFilter corsFilter,
    HealthFilter healthFilter,
    List<StaticResourceMount> staticMounts,
    List<HttpFilter> filters,
    List<ErrorHandler> errorHandlers
) {

    public RequestComponents {
        routes = routes == null ? new RouteIndex(List.of(), List.of()) : routes;
        websocketIndex = websocketIndex == null ? new WebSocketIndex(List.of(), List.of())
            : websocketIndex;
        corsFilter = corsFilter == null ? CorsFilter.defaults() : corsFilter;
        healthFilter = healthFilter == null ? HealthFilter.defaults() : healthFilter;
        staticMounts = staticMounts == null ? List.of() : List.copyOf(staticMounts);
        filters = filters == null ? List.of() : List.copyOf(filters);
        errorHandlers = errorHandlers == null ? List.of() : List.copyOf(errorHandlers);
    }

    /** A server with these routes and nothing else: the built-in CORS, health and
     *  error-mapping policies, no filters, no static mounts, no WebSocket routes. */
    public static RequestComponents of(Route... routes) {
        return new RequestComponents(new RouteIndex(List.of(routes), List.of()),
            null, null, null, List.of(), List.of(), List.of());
    }

    /** The same parts routing exactly these routes (replacing what was there). */
    public RequestComponents withRoutes(List<Route> routes) {
        return new RequestComponents(new RouteIndex(routes, List.of()), websocketIndex,
            corsFilter, healthFilter, staticMounts, filters, errorHandlers);
    }

    /** The same parts with these routes added to the ones already there. */
    public RequestComponents withRoutes(Route... more) {
        return withRoutes(List.of(more));
    }

    /** The same parts accepting these WebSocket endpoints. */
    public RequestComponents withWebSockets(List<WebSocketRoute> webSocketRoutes) {
        return new RequestComponents(routes,
            new WebSocketIndex(webSocketRoutes, List.of()),
            corsFilter, healthFilter, staticMounts, filters, errorHandlers);
    }

    /** The same parts with these WebSocket endpoints added. */
    public RequestComponents withWebSockets(WebSocketRoute... webSocketRoutes) {
        return withWebSockets(List.of(webSocketRoutes));
    }

    /** The same parts with this application filter appended (ordering is by
     *  {@link HttpFilter#order()}, not by the order they were added). */
    public RequestComponents withFilter(HttpFilter filter) {
        return withFilters(List.of(filter));
    }

    /** The same parts with these application filters appended. */
    public RequestComponents withFilters(HttpFilter... more) {
        return withFilters(List.of(more));
    }

    /** The same parts with these application filters appended. */
    public RequestComponents withFilters(List<HttpFilter> more) {
        var withMore = new ArrayList<>(filters);
        withMore.addAll(more);
        return new RequestComponents(routes, websocketIndex, corsFilter, healthFilter,
            staticMounts, withMore, errorHandlers);
    }

    /** The same parts serving this static mount. */
    public RequestComponents withStaticFiles(StaticResourceMount... mounts) {
        var withMore = new ArrayList<>(staticMounts);
        for (StaticResourceMount mount : mounts) {
            withMore.add(mount);
        }
        return new RequestComponents(routes, websocketIndex, corsFilter, healthFilter,
            withMore, filters, errorHandlers);
    }

    /** The same parts with this exception mapper consulted <em>before</em> the
     *  built-in one {@link WebServer#create} appends. */
    public RequestComponents withErrorMapper(ErrorHandler... handlers) {
        var withOneMore = new ArrayList<>(errorHandlers);
        withOneMore.addAll(List.of(handlers));
        return new RequestComponents(routes, websocketIndex, corsFilter, healthFilter,
            staticMounts, filters, withOneMore);
    }

    /** The same parts with a different CORS policy. */
    public RequestComponents withCors(CorsFilter corsFilter) {
        return new RequestComponents(routes, websocketIndex, corsFilter, healthFilter,
            staticMounts, filters, errorHandlers);
    }

    /** The same parts with a different health policy. */
    public RequestComponents withHealth(HealthFilter healthFilter) {
        return new RequestComponents(routes, websocketIndex, corsFilter, healthFilter,
            staticMounts, filters, errorHandlers);
    }
}
