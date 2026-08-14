package com.jujin.freeway.http;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.metrics.NoopMetrics;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.AccessLogFilter;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.ExceptionMappers;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.LazyHandler;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteGroup;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketRoute;

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
    private final List<WebSocketRoute> webSocketRoutes = new ArrayList<>();
    private final List<WebSocketGroup> webSocketGroups = new ArrayList<>();
    private final List<HttpFilter> filters = new ArrayList<>();
    private final List<ExceptionMapper> exceptionMappers = new ArrayList<>();
    private final List<StaticResourceMount> staticMounts = new ArrayList<>();

    private HttpServerConfig config = new HttpServerConfig(
        "127.0.0.1", 8080, 0, Duration.ofSeconds(2));
    private HttpEngine engine;
    private SSLContext sslContext;
    private boolean http2OverSsl;
    private CorsFilter corsFilter = CorsFilter.DEFAULT;
    private HealthFilter healthFilter = HealthFilter.DEFAULT;
    private Consumer<Object> eventSink = WebServer.NOOP_SINK;
    private JsonCodec jsonCodec = new JsonCodecDefault();
    private Coercer coercer = new CoercerDefault();
    private Metrics metrics = NoopMetrics.INSTANCE;

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

    public WebServerBuilder webSocketRoute(WebSocketRoute route) {
        webSocketRoutes.add(Objects.requireNonNull(route, "webSocketRoute"));
        return this;
    }

    public WebServerBuilder webSocketGroup(WebSocketGroup group) {
        webSocketGroups.add(Objects.requireNonNull(group, "webSocketGroup"));
        return this;
    }

    public WebServerBuilder filter(HttpFilter filter) {
        filters.add(Objects.requireNonNull(filter, "filter"));
        return this;
    }

    /** Enables a text access log written to the given stream. */
    public WebServerBuilder accessLog(PrintStream out) {
        return filter(new AccessLogFilter(out));
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

    /** Attaches a metrics implementation for engine-level counters/gauges. */
    public WebServerBuilder metrics(Metrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        return this;
    }

    /** Enables HTTPS on the server with the given SSL context and optional HTTP/2. */
    public WebServerBuilder sslContext(SSLContext sslContext, boolean http2OverSsl) {
        this.sslContext = Objects.requireNonNull(sslContext, "sslContext");
        this.http2OverSsl = http2OverSsl;
        return this;
    }

    public WebServer build() {
        if (engine == null) {
            engine = sslContext != null
                ? new FreewayHttpEngine(
                    jsonCodec, coercer, sslContext, http2OverSsl,
                    null, metrics)
                : new FreewayHttpEngine(jsonCodec, coercer, metrics);
        }
        // Class-based routes resolve via container.create() — they need the IoC
        // HttpModule. Fail fast here instead of blowing up on the first request.
        for (Route r : routes) {
            checkNoLazyHandler(r);
        }
        for (RouteGroup group : routeGroups) {
            for (Route r : group.expand()) {
                checkNoLazyHandler(r);
            }
        }
        var routeIndex = new RouteIndex(routes, routeGroups);
        var wsIndex = new WebSocketIndex(webSocketRoutes, webSocketGroups);
        // Custom mappers run first (first handler wins); the built-in mapper
        // is always appended so BodyTooLarge→413, Multipart→400 and
        // Validation→400 keep working alongside custom mappers — matching the
        // HttpModule contribution semantics (container.extension() + built-in).
        var mappers = new ArrayList<>(exceptionMappers);
        mappers.add(ExceptionMappers.defaultMapper());
        var pipeline = new RequestPipeline(
            routeIndex, wsIndex, corsFilter, healthFilter,
            List.copyOf(staticMounts),
            List.copyOf(filters),
            List.copyOf(mappers)
        );
        return new WebServer(engine, config, eventSink, pipeline);
    }

    /** Class-based routes resolve via container.create() and need the IoC
     *  HttpModule; standalone builder mode can only use lambda handlers. */
    private static void checkNoLazyHandler(Route r) {
        if (r.handler() instanceof LazyHandler) {
            throw new IllegalStateException(
                "Class-based routes (Route.get(path, Handler.class)) require the IoC HttpModule, "
                    + "which instantiates handler classes with constructor injection. "
                    + "In standalone WebServerBuilder mode use lambda handlers instead.");
        }
    }

}
