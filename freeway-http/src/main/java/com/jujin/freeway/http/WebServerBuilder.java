package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteGroup;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Builder for standalone {@link WebServer} usage without the IoC container.
 *
 * <pre>{@code
 * var server = WebServer.builder()
 *     .config(new HttpServerConfig("0.0.0.0", 8080, 0, Duration.ofSeconds(2)))
 *     .route(Route.get("/ping", ctx -> ctx.send(200, "pong")))
 *     .build();
 * server.start();
 * }</pre>
 */
public final class WebServerBuilder {

    private final List<Route> routes = new ArrayList<>();
    private final List<RouteGroup> routeGroups = new ArrayList<>();
    private final List<WebSocketRoute> wsRoutes = new ArrayList<>();
    private final List<WebSocketGroup> wsGroups = new ArrayList<>();
    private final List<HttpFilter> filters = new ArrayList<>();
    private final List<ExceptionMapper> exceptionMappers = new ArrayList<>();
    private final List<StaticResourceMount> staticMounts = new ArrayList<>();

    private HttpServerConfig config = new HttpServerConfig(
        "127.0.0.1", 8080, 0, Duration.ofSeconds(2));
    private HttpEngine engine;
    private CorsFilter corsFilter = CorsFilter.DEFAULT;
    private HealthFilter healthFilter = HealthFilter.DEFAULT;
    private Consumer<Object> eventSink = WebServer.NOOP_SINK;
    private JsonCodec jsonCodec = new JsonCodecDefault();
    private Coercer coercer = new CoercerDefault();

    WebServerBuilder() {}

    public static WebServerBuilder builder() {
        return new WebServerBuilder();
    }

    public WebServerBuilder config(HttpServerConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    public WebServerBuilder engine(HttpEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
        return this;
    }

    public WebServerBuilder route(Route route) {
        routes.add(Objects.requireNonNull(route, "route"));
        return this;
    }

    public WebServerBuilder routeGroup(RouteGroup group) {
        routeGroups.add(Objects.requireNonNull(group, "group"));
        return this;
    }

    public WebServerBuilder wsRoute(WebSocketRoute route) {
        wsRoutes.add(Objects.requireNonNull(route, "wsRoute"));
        return this;
    }

    public WebServerBuilder wsGroup(WebSocketGroup group) {
        wsGroups.add(Objects.requireNonNull(group, "wsGroup"));
        return this;
    }

    public WebServerBuilder filter(HttpFilter filter) {
        filters.add(Objects.requireNonNull(filter, "filter"));
        return this;
    }

    public WebServerBuilder exceptionMapper(ExceptionMapper mapper) {
        exceptionMappers.add(Objects.requireNonNull(mapper, "mapper"));
        return this;
    }

    public WebServerBuilder staticFile(StaticResourceMount mount) {
        staticMounts.add(Objects.requireNonNull(mount, "mount"));
        return this;
    }

    public WebServerBuilder cors(CorsFilter corsFilter) {
        this.corsFilter = Objects.requireNonNull(corsFilter, "corsFilter");
        return this;
    }

    public WebServerBuilder health(HealthFilter healthFilter) {
        this.healthFilter = Objects.requireNonNull(healthFilter, "healthFilter");
        return this;
    }

    public WebServerBuilder eventSink(Consumer<Object> eventSink) {
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        return this;
    }

    public WebServerBuilder jsonCodec(JsonCodec jsonCodec) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        return this;
    }

    public WebServerBuilder coercer(Coercer coercer) {
        this.coercer = Objects.requireNonNull(coercer, "coercer");
        return this;
    }

    public WebServer build() {
        if (engine == null) {
            engine = new FreewayHttpEngine(jsonCodec, coercer);
        }
        var routeIndex = new RouteIndex(routes, routeGroups);
        var wsIndex = new WebSocketIndex(wsRoutes, wsGroups);
        var pipeline = new RequestPipeline(
            routeIndex, wsIndex, corsFilter, healthFilter,
            List.copyOf(staticMounts),
            List.copyOf(filters),
            List.copyOf(exceptionMappers)
        );
        return new WebServer(engine, config, eventSink, pipeline);
    }
}
