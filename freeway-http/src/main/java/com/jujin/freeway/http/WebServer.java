package com.jujin.freeway.http;

import java.io.IOException;
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
import com.jujin.freeway.http.event.HttpExchangeEvent;
import com.jujin.freeway.http.event.HttpServerStartedEvent;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ErrorHandler;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
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

    private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);
    static final Consumer<Object> NOOP_SINK = event -> {};

    private final RouteIndex routes;
    private final WebSocketIndex websocketIndex;
    private final CorsFilter corsFilter;
    private final List<StaticResourceMount> staticMounts;
    private final List<HttpFilter> filters;
    private final List<ErrorHandler> errorHandlers;
    private final HttpEngine engine;
    private final HttpServerConfig config;
    private final Consumer<Object> eventSink;
    private final ReadinessProbe readinessProbe;
    private final ExchangeHandler exchangeHandler;
    private final RouteHandler filterChain;
    private final boolean publishEvents;

    private volatile HttpServerHandle handle;

    /**
     * The server for these parts, publishing nothing: the standalone shape, where
     * no one is listening so no per-request event objects are built.
     */
    public static WebServer create(HttpEngine engine, HttpServerConfig config,
            RequestComponents components) {
        return create(engine, config, components, NOOP_SINK);
    }

    /**
     * The one derivation of a server from its parts — what {@link HttpModule}
     * calls with the container's {@code EventBus} as the sink, and what a caller
     * without a container calls directly. Both paths get the same two policies
     * because they are stated only here: the built-in exception mapper is
     * consulted last, and a server is ready when its engine reports a bound port.
     *
     * @param eventSink  receives {@link HttpExchangeEvent} / {@link HttpErrorEvent}
     *                   and the start event; {@link #create(HttpEngine,
     *                   HttpServerConfig, RequestComponents)} passes the no-op sink
     */
    public static WebServer create(HttpEngine engine, HttpServerConfig config,
            RequestComponents components, Consumer<Object> eventSink) {
        return new WebServer(engine, config, eventSink, builtInMapperLast(components),
            (host, port) -> port > 0);
    }

    /**
     * The framework's own mapper, appended after the application's: the list's
     * rule is "first mapper that claims the exception wins", so appending is what
     * lets an application re-map a case the built-in mappings cover (an oversized
     * body, a failed validation) without losing the ones it does not handle.
     */
    private static RequestComponents builtInMapperLast(RequestComponents components) {
        var mappers = new ArrayList<ErrorHandler>(components.errorHandlers().size() + 1);
        mappers.addAll(components.errorHandlers());
        mappers.add(ErrorHandler.defaults());
        return new RequestComponents(components.routes(), components.websocketIndex(),
            components.corsFilter(), components.healthFilter(), components.staticMounts(),
            components.filters(), mappers);
    }

    /**
     * Assembled by {@link HttpModule} or {@link #create}: every part is in hand
     * here, and {@link #secure()} is correct for any server that exists because
     * it is the engine's own verdict, not something an assembler could get wrong.
     *
     * <p>The 4-argument form that used to sit here hard-coded {@code secure =
     * false}, and the standalone builder that replaced it re-derived the verdict
     * a second time; both are gone, and the verdict has one owner.</p>
     */
    WebServer(
        HttpEngine engine,
        HttpServerConfig config,
        Consumer<Object> eventSink,
        RequestComponents pipeline,
        ReadinessProbe readinessProbe
    ) {
        this.routes = Objects.requireNonNull(pipeline.routes(), "routes");
        this.websocketIndex = Objects.requireNonNull(pipeline.websocketIndex(), "websocketIndex");
        this.corsFilter = Objects.requireNonNull(pipeline.corsFilter(), "corsFilter");
        this.staticMounts = pipeline.staticMounts() != null ? pipeline.staticMounts() : List.of();
        this.errorHandlers = pipeline.errorHandlers();
        // Application filters plus the built-in CORS/health filters share
        // one ordered chain; inactive built-ins are skipped entirely so a
        // no-op request never pays a virtual call.
        var orderedFilters = new ArrayList<>(pipeline.filters());
        HealthFilter healthFilter = Objects.requireNonNull(
            pipeline.healthFilter(), "healthFilter");
        if (healthFilter.isActive()) orderedFilters.add(healthFilter);
        if (corsFilter.isActive()) orderedFilters.add(corsFilter);
        orderedFilters.sort(Comparator.comparingInt(HttpFilter::order));
        this.filters = List.copyOf(orderedFilters);
        this.filterChain = buildChain(this::dispatchToRoute, this.filters);
        this.engine = Objects.requireNonNull(engine, "engine");
        this.config = Objects.requireNonNull(config, "config");
        this.eventSink = eventSink != null ? eventSink : NOOP_SINK;
        this.readinessProbe = Objects.requireNonNull(readinessProbe, "readinessProbe");
        // Skip event computation when the sink is the noop sentinel
        this.publishEvents = this.eventSink != NOOP_SINK;

        RouteHandler request = ctx -> {
            try {
                filterChain.handle(ctx);
            } catch (Exception ex) {
                boolean handled = WebServer.this.handleException(ctx, ex);
                if (!handled && publishEvents) {
                    publish(new HttpErrorEvent(
                        ctx.method(), ctx.path(), ex));
                }
            }
            boolean debug = LOG.isDebugEnabled();
            if (debug || publishEvents) {
                long elapsed = Duration.between(
                    ctx.startTime(), Instant.now()).toMillis();
                if (debug) {
                    LOG.debug("{} {} -> {} ({} ms, id={})",
                        ctx.method(), ctx.path(),
                        ctx.status(), elapsed, ctx.correlationId());
                }
                if (publishEvents) {
                    publish(new HttpExchangeEvent(
                        ctx.method(), ctx.path(),
                        ctx.status(), elapsed));
                }
            }
        };

        this.exchangeHandler = new ExchangeHandler() {
            @Override
            public void handle(HttpContext ctx) throws Exception {
                request.handle(ctx);
            }

            @Override
            public WebSocketMatch websocket(
                String method,
                String path,
                String origin
            ) {
                if (corsFilter.isActive()) {
                    String allowed = corsFilter.resolveAllowedOrigin(origin);
                    if (allowed == null && origin != null && !origin.isBlank()) {
                        LOG.debug(
                            "WebSocket upgrade rejected: origin '{}' not allowed for {}",
                            origin,
                            path
                        );
                        return null;
                    }
                }
                return websocketIndex.match(method, path);
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

    /**
     * Whether this server serves TLS — the engine's verdict, read through
     * {@link HttpEngine#secure()}. An engine that terminates TLS answers true
     * whatever the configuration says, and an adapter that ignores the built-in
     * {@code freeway.http.ssl.*} keys cannot make this server claim otherwise.
     * Answers the scheme question for collaborators that build the node's
     * externally visible identity (the cloud registry endpoint, the event mesh
     * origin), so they do not re-derive another module's presence rule.
     */
    public boolean secure() {
        return engine.secure();
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
                h = engine.start(config, exchangeHandler);
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
                        "Web server did not become ready on " + h.host() + ":" + h.port());
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

    private void dispatchToRoute(HttpContext ctx) throws Exception {
        if (!staticMounts.isEmpty()) {
            boolean anyMountMatched = false;
            boolean fallthroughMiss = false;
            for (StaticResourceMount mount : staticMounts) {
                if (!mount.matches(ctx.method(), ctx.path())) {
                    continue;
                }
                anyMountMatched = true;
                if (mount.hasResource(ctx.path())) {
                    // Only a real asset commits a response here; a mount that
                    // matched but has no asset must not shadow later mounts or
                    // the route chain with a premature 404.
                    mount.serve(ctx, ctx);
                    return;
                }
                if (mount.fallthrough()) {
                    fallthroughMiss = true;
                }
            }
            if (anyMountMatched && !fallthroughMiss) {
                // Every matching mount is a non-fallthrough miss — a terminal
                // 404, mirroring a single mount's direct serve() result.
                ErrorResponses.notFound(ctx);
                return;
            }
        }
        RouteIndex.RouteMatch match = routes.match(
            ctx.method(),
            ctx.path()
        );
        if (match == null) {
            ErrorResponses.notFound(ctx);
            return;
        }
        ctx.setPathVars(match.pathVariables());
        match.handler().handle(ctx);
    }

    /** Commits the standard plain-text 404 response. */
    private boolean handleException(HttpContext ctx, Exception exception) {
        for (ErrorHandler handler : errorHandlers) {
            try {
                if (handler.handle(ctx, exception)) return true;
            } catch (Exception handlerEx) {
                LOG.warn(
                    "Error handler {} failed while handling {}",
                    handler.getClass().getSimpleName(),
                    String.valueOf(exception.getMessage()),
                    handlerEx
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
            ErrorResponses.internalError(ctx);
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

}
