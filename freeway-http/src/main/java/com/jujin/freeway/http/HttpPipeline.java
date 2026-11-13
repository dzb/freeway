package com.jujin.freeway.http;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ErrorMapper;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketRoute;

/**
 * The request-handling pipeline a {@link HttpServer} is derived from — one of
 * the server's four anchors ({@link HttpEngine} capability, {@link
 * HttpServerConfig} transport declaration, this pipeline's handling
 * declaration, {@link HttpServer} derivation). Both assembly paths share it:
 * {@link HttpModule} fills it from the container's contributions, and a caller
 * with no container fills it by hand (or starts from {@link #of(Route...)},
 * which is the whole story for a server with nothing but routes).
 *
 * <p>Stage order for one exchange: the WebSocket upgrade decision is consulted
 * first, before the filter chain ({@link HttpServer} answers it through
 * {@link ExchangeHandler#matchWebSocket(String, String, String)}); the filter chain
 * then wraps dispatch, where static mounts are tried before route dispatch and
 * an opt-in history-mode SPA fallback is tried after route dispatch misses; and
 * the error-mapping list wraps the whole chain. Nothing here is engine-specific:
 * {@link HttpServer#create} compiles this pipeline into a single
 * {@link ExchangeHandler} <em>before</em> {@code engine.start}, so every
 * {@link HttpEngine} — built-in or third-party — serves the same pipeline
 * without seeing its parts.
 *
 * <p>It carries declarations, not assembly policy: the built-in error mapper is
 * appended and the inactive filters skipped by {@link HttpServer#create}, so
 * neither rule can be stated differently on the two paths.
 */
public record HttpPipeline(
    RouteIndex routes,
    WebSocketIndex webSocketIndex,
    CorsFilter corsFilter,
    HealthFilter healthFilter,
    List<StaticResourceMount> staticMounts,
    List<HttpFilter> filters,
    List<ErrorMapper> errorMappers
) {

    /** Every part is required: a container binding that answers null fails
     *  startup here instead of silently falling back to a default policy. */
    public HttpPipeline {
        routes = Objects.requireNonNull(routes, "routes");
        webSocketIndex = Objects.requireNonNull(webSocketIndex, "webSocketIndex");
        corsFilter = Objects.requireNonNull(corsFilter, "corsFilter");
        healthFilter = Objects.requireNonNull(healthFilter, "healthFilter");
        staticMounts = List.copyOf(Objects.requireNonNull(staticMounts, "staticMounts"));
        filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        errorMappers = List.copyOf(Objects.requireNonNull(errorMappers, "errorMappers"));
    }

    /**
     * The final filter chain this pipeline declares: application filters plus
     * active built-ins, sorted once by {@link HttpFilter#order()}. The server
     * consumes this list; it does not need to know which filters were named
     * slots and which came from the application list.
     */
    public List<HttpFilter> filtersInOrder() {
        var ordered = new ArrayList<HttpFilter>(filters);
        if (healthFilter.isActive()) {
            ordered.add(healthFilter);
        }
        if (corsFilter.isActive()) {
            ordered.add(corsFilter);
        }
        ordered.sort(Comparator.comparingInt(HttpFilter::order));
        return List.copyOf(ordered);
    }

    /** A server with these routes and nothing else: the built-in CORS, health and
     *  error-mapping policies, no filters, no static mounts, no WebSocket routes. */
    public static HttpPipeline of(Route... routes) {
        return new HttpPipeline(new RouteIndex(List.of(routes)),
            new WebSocketIndex(List.of()),
            CorsFilter.defaults(), HealthFilter.defaults(),
            List.of(), List.of(), List.of());
    }

    /** The same pipeline routing exactly these routes (replacing what was
     *  there): a {@code RouteIndex} is a frozen trie, so the wither replaces,
     *  never appends. */
    public HttpPipeline withRoutes(List<Route> routes) {
        return new HttpPipeline(new RouteIndex(routes), webSocketIndex,
            corsFilter, healthFilter, staticMounts, filters, errorMappers);
    }

    /** The same pipeline routing exactly these routes (replacing what was there). */
    public HttpPipeline withRoutes(Route... routes) {
        return withRoutes(List.of(routes));
    }

    /** The same pipeline accepting exactly these WebSocket endpoints (replacing
     *  what was there). */
    public HttpPipeline withWebSockets(List<WebSocketRoute> webSocketRoutes) {
        return new HttpPipeline(routes,
            new WebSocketIndex(webSocketRoutes),
            corsFilter, healthFilter, staticMounts, filters, errorMappers);
    }

    /** The same pipeline accepting exactly these WebSocket endpoints (replacing
     *  what was there). */
    public HttpPipeline withWebSockets(WebSocketRoute... webSocketRoutes) {
        return withWebSockets(List.of(webSocketRoutes));
    }

    /** The same pipeline with this application filter appended (ordering is by
     *  {@link HttpFilter#order()}, not by the order they were added). */
    public HttpPipeline withFilter(HttpFilter filter) {
        return withFilters(List.of(filter));
    }

    /** The same pipeline with these application filters appended. */
    public HttpPipeline withFilters(HttpFilter... more) {
        return withFilters(List.of(more));
    }

    /** The same pipeline with these application filters appended. */
    public HttpPipeline withFilters(List<HttpFilter> more) {
        var withMore = new ArrayList<>(filters);
        withMore.addAll(more);
        return new HttpPipeline(routes, webSocketIndex, corsFilter, healthFilter,
            staticMounts, withMore, errorMappers);
    }

    /** The same pipeline serving this static mount. */
    public HttpPipeline withStaticFiles(StaticResourceMount... mounts) {
        var withMore = new ArrayList<>(staticMounts);
        for (StaticResourceMount mount : mounts) {
            withMore.add(mount);
        }
        return new HttpPipeline(routes, webSocketIndex, corsFilter, healthFilter,
            withMore, filters, errorMappers);
    }

    /** The same pipeline with these exception mappers appended; they are
     *  consulted <em>before</em> the built-in mapper that
     *  {@link HttpServer#create} appends. */
    public HttpPipeline withErrorMappers(ErrorMapper... handlers) {
        var withOneMore = new ArrayList<>(errorMappers);
        withOneMore.addAll(List.of(handlers));
        return new HttpPipeline(routes, webSocketIndex, corsFilter, healthFilter,
            staticMounts, filters, withOneMore);
    }

    /** The same pipeline with a different CORS policy. */
    public HttpPipeline withCors(CorsFilter corsFilter) {
        return new HttpPipeline(routes, webSocketIndex, corsFilter, healthFilter,
            staticMounts, filters, errorMappers);
    }

    /** The same pipeline with a different health policy. */
    public HttpPipeline withHealth(HealthFilter healthFilter) {
        return new HttpPipeline(routes, webSocketIndex, corsFilter, healthFilter,
            staticMounts, filters, errorMappers);
    }
}
