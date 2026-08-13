package com.jujin.freeway.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.http.HttpContext;
import com.jujin.freeway.http.event.HttpErrorEvent;
import com.jujin.freeway.http.event.HttpRequestEvent;
import com.jujin.freeway.http.event.HttpServerStartedEvent;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.filter.RequestTimingFilter;
import com.jujin.freeway.http.route.RouteHandler;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketMatch;

/**
 * Orchestrates HTTP request handling: filter chain, route dispatch, static
 * files, WebSocket upgrades, error mapping, and event publishing.
 */
public final class WebServer implements AutoCloseable {

    // Pre-computed error response bodies (UTF-8)
    private static final byte[] NOT_FOUND_BODY = "Not Found".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INTERNAL_ERROR_BODY = "Internal Server Error".getBytes(StandardCharsets.UTF_8);

    private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);
    static final Consumer<Object> NOOP_SINK = event -> {};

    private final RouteIndex routes;
    private final WebSocketIndex websocketIndex;
    private final CorsFilter corsFilter;
    private final List<StaticResourceMount> staticMounts;
    private final List<HttpFilter> filters;
    private final List<ExceptionMapper> mappers;
    private final RequestTimingFilter timingFilter;
    private final HttpEngine engine;
    private final HttpServerConfig config;
    private final Consumer<Object> eventSink;
    private final ReadinessProbe readinessProbe;
    private final HttpRequestHandler requestHandler;
    private final RouteHandler filterChain;
    private final boolean publishEvents;

    private volatile HttpServerHandle handle;

    public WebServer(
        HttpEngine engine,
        HttpServerConfig config,
        Consumer<Object> eventSink,
        RequestPipeline pipeline
    ) {
        this(engine, config, eventSink, pipeline, (host, port) -> port > 0);
    }

    WebServer(
        HttpEngine engine,
        HttpServerConfig config,
        Consumer<Object> eventSink,
        RequestPipeline pipeline,
        ReadinessProbe readinessProbe
    ) {
        this.routes = Objects.requireNonNull(pipeline.routes(), "routes");
        this.websocketIndex = Objects.requireNonNull(pipeline.websocketIndex(), "websocketIndex");
        this.corsFilter = Objects.requireNonNull(pipeline.corsFilter(), "corsFilter");
        this.staticMounts = pipeline.staticMounts() != null ? pipeline.staticMounts() : List.of();
        PreparedFilters preparedFilters = prepareFilters(pipeline.filters());
        this.timingFilter = preparedFilters.timingFilter();
        this.mappers = pipeline.mappers();
        // Application filters plus the built-in CORS/health filters share
        // one ordered chain; inactive built-ins are skipped entirely so a
        // no-op request never pays a virtual call.
        var orderedFilters = new ArrayList<>(preparedFilters.filters());
        HealthFilter healthFilter = Objects.requireNonNull(
            pipeline.healthFilter(), "healthFilter");
        if (healthFilter.isActive()) orderedFilters.add(healthFilter);
        if (corsFilter.isActive()) orderedFilters.add(corsFilter);
        orderedFilters.sort(Comparator.comparingInt(HttpFilter::order));
        this.filters = List.copyOf(orderedFilters);
        this.filterChain = buildChain(this::dispatchToRoute, this.filters);
        this.engine = Objects.requireNonNull(engine, "engine");
        this.config = Objects.requireNonNull(config, "config");
        this.eventSink = eventSink != null ? eventSink : event -> {};
        this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe");
        // Skip event computation when sink is the noop sentinel
        this.publishEvents = this.eventSink != NOOP_SINK;

        RouteHandler timedChain = publishEvents
            ? wrapWithEvents(this.filterChain)
            : wrapWithErrorHandling(this.filterChain);

        this.requestHandler = new HttpRequestHandler() {
            @Override
            public void handle(HttpContext ctx) throws Exception {
                timingFilter.doFilter(ctx, timedChain);
            }

            @Override
            public WebSocketMatch websocket(
                String method,
                String path,
                String origin
            ) {
                String allowed = corsFilter.resolveAllowedOrigin(origin);
                if (allowed == null && origin != null && !origin.isBlank()) {
                    LOG.debug(
                        "WebSocket upgrade rejected: origin '{}' not allowed for {}",
                        origin,
                        path
                    );
                    return null;
                }
                return websocketIndex.match(method, path);
            }
        };
    }

    /** Wraps the chain with error handling + request/error events. */
    private RouteHandler wrapWithEvents(RouteHandler inner) {
        return ctx -> {
            try {
                inner.handle(ctx);
            } catch (Exception ex) {
                boolean handled = WebServer.this.handleException(ctx, ex);
                if (!handled) {
                    publish(new HttpErrorEvent(
                        ctx.method(), ctx.path(), ex));
                }
            }
            long elapsed = Duration.between(
                ctx.startTime(), Instant.now()).toMillis();
            publish(new HttpRequestEvent(
                ctx.method(), ctx.path(),
                ctx.status(), elapsed));
        };
    }

    /** Wraps the chain with error handling only (no event publishing). */
    private RouteHandler wrapWithErrorHandling(RouteHandler inner) {
        return ctx -> {
            try {
                inner.handle(ctx);
            } catch (Exception ex) {
                WebServer.this.handleException(ctx, ex);
            }
        };
    }

    public String host() {
        return requireStarted().host();
    }

    public int port() {
        return requireStarted().port();
    }

    public synchronized void start() {
        ensureStarted();
    }

    public synchronized void stop() {
        close();
    }

    public boolean isRunning() {
        return handle != null;
    }

    @Override
    public synchronized void close() {
        HttpServerHandle h = this.handle;
        if (h == null) {
            return;
        }
        this.handle = null;
        h.close();
        LOG.info("Freeway web server stopped");
    }

    private HttpServerHandle requireStarted() {
        HttpServerHandle h = this.handle;
        if (h == null) {
            throw new IllegalStateException("WebServer is not started");
        }
        return h;
    }

    private HttpServerHandle ensureStarted() {
        HttpServerHandle h = this.handle;
        if (h != null) {
            return h;
        }
        synchronized (this) {
            h = this.handle;
            if (h != null) {
                return h;
            }
            try {
                h = engine.start(config, requestHandler);
            } catch (IOException ex) {
                throw new RuntimeException(
                    "Failed to start HTTP engine", ex);
            }
            boolean closed = false;
            try {
                if (!readinessProbe.ready(h.host(), h.port())) {
                    closed = true;
                    closeQuietly(h);
                    throw new RuntimeException(
                        "Web server did not become ready on " + h.host() + ":" + h.port()
                            + " within 10s");
                }
                this.handle = h;
                LOG.info("Freeway web server started on {}:{}", h.host(), h.port());
                publish(new HttpServerStartedEvent(h.host(), h.port()));
                return h;
            } catch (RuntimeException ex) {
                if (!closed) {
                    closeQuietly(h);
                }
                throw ex;
            }
        }
    }

    private static void closeQuietly(HttpServerHandle handle) {
        if (handle == null) return;
        try { handle.close(); } catch (Exception ignored) {}
    }

    /** Internal seam: lets package-local tests verify the readiness gate
     *  without binding a real socket. Not part of the public API. */
    @FunctionalInterface
    interface ReadinessProbe {
        boolean ready(String host, int port);
    }

    private RouteHandler buildChain(
        RouteHandler handler,
        List<HttpFilter> filters
    ) {
        if (filters.isEmpty()) return handler;
        RouteHandler chain = handler;
        for (int i = filters.size() - 1; i >= 0; i--) {
            HttpFilter filter = filters.get(i);
            RouteHandler next = chain;
            chain = ctx -> filter.doFilter(ctx, next);
        }
        return chain;
    }

    private PreparedFilters prepareFilters(List<HttpFilter> filters) {
        List<HttpFilter> items = new ArrayList<>();
        RequestTimingFilter timing = null;
        for (HttpFilter filter : filters == null
            ? List.<HttpFilter>of()
            : filters) {
            if (filter instanceof RequestTimingFilter candidate) {
                if (timing == null) timing = candidate;
            } else {
                items.add(filter);
            }
        }
        return new PreparedFilters(
            items,
            timing != null ? timing : new RequestTimingFilter()
        );
    }

    private void dispatchToRoute(HttpContext ctx) throws Exception {
        if (!staticMounts.isEmpty()) {
            for (StaticResourceMount mount : staticMounts) {
                if (mount.matches(ctx.method(), ctx.path())) {
                    if (mount.serve(ctx, ctx)) return;
                }
            }
        }
        RouteIndex.RouteMatch match = routes.match(
            ctx.method(),
            ctx.path()
        );
        if (match == null) {
            ctx.status(404).setHeader(
                "Content-Type", "text/plain; charset=utf-8")
                .output(NOT_FOUND_BODY);
            return;
        }
        ctx.pathVars(match.pathVariables());
        match.handler().handle(ctx);
    }

    private boolean handleException(HttpContext ctx, Exception exception) {
        for (ExceptionMapper mapper : mappers) {
            try {
                if (mapper.handle(ctx, exception)) return true;
            } catch (Exception mapperEx) {
                LOG.warn(
                    "Exception mapper {} failed while handling {}",
                    mapper.getClass().getSimpleName(),
                    String.valueOf(exception.getMessage()),
                    mapperEx
                );
            }
        }
        if (exception instanceof IOException
                && ctx.isResponded()) {
            // The response was already committed when the transport failed —
            // the peer disconnected mid-write. A 500 cannot be delivered and
            // this is an expected lifecycle event under concurrency (client
            // aborts, keep-alive races), not an application error. Keep it
            // quiet; the session layer still traces it. An IOException raised
            // before the response commits is still an application error.
            LOG.debug("Connection error for {} {}: {}: {}",
                ctx.method(), ctx.path(),
                exception.getClass().getSimpleName(),
                String.valueOf(exception.getMessage()));
            return true;
        }
        LOG.error(
            "Unhandled exception for {} {}: {}: {}",
            ctx.method(),
            ctx.path(),
            exception.getClass().getSimpleName(),
            String.valueOf(exception.getMessage())
        );
        try {
            ctx.status(500).setHeader(
                "Content-Type", "text/plain; charset=utf-8")
                .output(INTERNAL_ERROR_BODY);
        } catch (Exception sendEx) {
            LOG.error("Failed to send error response", sendEx);
        }
        return false;
    }

    private void publish(Object event) {
        try {
            eventSink.accept(event);
        } catch (Exception ex) {
            LOG.debug("Event publish failed for {}: {}",
                event.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private record PreparedFilters(
        List<HttpFilter> filters,
        RequestTimingFilter timingFilter
    ) {}
}
