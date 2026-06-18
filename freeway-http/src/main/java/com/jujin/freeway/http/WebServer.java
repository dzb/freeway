package com.jujin.freeway.http;

import com.jujin.freeway.commons.defer.Defer;
import com.jujin.freeway.http.event.*;
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
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.annotation.Value;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WebServer.class);

    private final RouteIndex routes;
    private final WebSocketIndex websocketIndex;
    private final CorsFilter corsFilter;
    private final List<StaticResourceMount> staticMounts;
    private final List<HttpFilter> filters;
    private final List<ExceptionMapper> mappers;
    private final RequestTimingFilter timingFilter;
    private final Container container;
    private final String webEngineId;
    private final HttpServerConfig config;
    private final HealthFilter healthFilter;
    private final HttpRequestHandler requestHandler;
    private final RouteHandler filterChain;

    private volatile HttpServerHandle handle;

    public WebServer(
        RouteIndex routes,
        WebSocketIndex websocketIndex,
        CorsFilter corsFilter,
        HealthFilter healthFilter,
        List<StaticResourceMount> staticMounts,
        List<HttpFilter> filters,
        List<ExceptionMapper> mappers,
        Container container,
        @Value("${web.engine:robaho}") String webEngineId,
        @Value("${web.server.host:127.0.0.1}") String host,
        @Value("${web.server.port:8080}") int port,
        @Value("${web.server.backlog:0}") int backlog,
        @Value(
            "${web.server.shutdown-grace-seconds:2}"
        ) int shutdownGraceSeconds
    ) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.websocketIndex = Objects.requireNonNull(
            websocketIndex,
            "websocketIndex"
        );
        this.corsFilter = Objects.requireNonNull(corsFilter, "corsFilter");
        this.staticMounts = staticMounts;
        PreparedFilters preparedFilters = prepareFilters(filters);
        this.filters = List.copyOf(preparedFilters.filters());
        this.timingFilter = preparedFilters.timingFilter();
        this.mappers = mappers;
        this.filterChain = buildChain(this::dispatchToRoute, this.filters);
        this.container = Objects.requireNonNull(container, "container");
        this.webEngineId = webEngineId;
        this.healthFilter = Objects.requireNonNull(
            healthFilter,
            "healthFilter"
        );
        this.config = new HttpServerConfig(
            host,
            port,
            backlog,
            shutdownGraceSeconds
        );
        this.requestHandler = new HttpRequestHandler() {
            @Override
            public void handle(HttpContext ctx) throws Exception {
                Defer.within(() -> {
                    try {
                        timingFilter.doFilter(ctx, request -> {
                            try {
                                processRequest(request);
                            } catch (Exception ex) {
                                handleException(request, ex);
                            }
                        });
                    } catch (Exception ex) {
                        handleException(ctx, ex);
                        publish(
                            new HttpErrorEvent(ctx.method(), ctx.path(), ex)
                        );
                    }
                    long elapsed = Duration.between(
                        ctx.requestContext().startTime(),
                        Instant.now()
                    ).toMillis();
                    publish(
                        new HttpRequestEvent(
                            ctx.method(),
                            ctx.path(),
                            ctx.statusCode(),
                            elapsed
                        )
                    );
                });
            }

            @Override
            public WebSocketMatch websocket(
                String method,
                String path,
                String origin
            ) {
                String allowed = corsFilter.resolveAllowedOrigin(origin);
                if (allowed == null && origin != null && !origin.isBlank()) {
                    LOG.warn(
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
        HttpServerHandle h = this.handle;
        if (h == null) {
            return;
        }
        this.handle = null;
        h.close();
        LOG.info("Freeway web server stopped");
    }

    public boolean isRunning() {
        return handle != null;
    }

    @Override
    public void close() {
        stop();
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
            HttpEngine engine = resolveEngine(container, webEngineId);
            try {
                h = engine.start(config, requestHandler);
            } catch (IOException ex) {
                throw new RuntimeException(
                    "Failed to start web engine " + webEngineId,
                    ex
                );
            }
            boolean closed = false;
            try {
                if (!awaitReady(h.host(), h.port())) {
                    closed = true;
                    closeQuietly(h);
                    throw new RuntimeException(
                        "Web server did not become ready on " + h.host() + ":" + h.port()
                            + " within 10s — engine started but not accepting HTTP"
                    );
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
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception ignored) {
            // Startup is already failing; keep the original exception.
        }
    }

    private static boolean awaitReady(String host, int port) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket(host, port)) {
                s.setSoTimeout(1000);
                s.getOutputStream().write(
                    "GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8)
                );
                s.getOutputStream().flush();
                byte[] buf = new byte[12];
                int read = s.getInputStream().read(buf);
                if (read >= 5) {
                    String response = new String(
                        buf,
                        0,
                        read,
                        StandardCharsets.US_ASCII
                    );
                    if (response.startsWith("HTTP/")) {
                        return true;
                    }
                }
            } catch (IOException ignored) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static HttpEngine resolveEngine(
        Container container,
        String webEngineId
    ) {
        String engineId = HttpContext.blankToNull(webEngineId);
        if (engineId == null) {
            engineId = "robaho";
        }
        try {
            return container.get(HttpEngine.class, engineId);
        } catch (RuntimeException ex) {
            if (!"robaho".equals(engineId)) {
                throw new IllegalStateException(
                    "Unable to resolve web engine " + engineId,
                    ex
                );
            }
            if (Boolean.getBoolean("freeway.strict")) {
                throw new IllegalStateException(
                    "Default engine 'robaho' is not available in strict mode",
                    ex
                );
            }
            LOG.warn(
                "Default engine 'robaho' not found, falling back to built-in JDK engine"
            );
            try {
                return container.get(HttpEngine.class, "jdk");
            } catch (RuntimeException fallbackEx) {
                throw new IllegalStateException(
                    "Built-in JDK engine is not available",
                    fallbackEx
                );
            }
        }
    }

    private RouteHandler buildChain(
        RouteHandler handler,
        List<HttpFilter> filters
    ) {
        if (filters.isEmpty()) {
            return handler;
        }
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
                if (timing == null) {
                    timing = candidate;
                }
            } else {
                items.add(filter);
            }
        }
        return new PreparedFilters(
            items,
            timing != null ? timing : new RequestTimingFilter()
        );
    }

    private void processRequest(HttpContext ctx) throws Exception {
        RouteHandler chain = next ->
            healthFilter.doFilter(next, this.filterChain);
        corsFilter.doFilter(ctx, chain);
    }

    private void dispatchToRoute(HttpContext request) throws Exception {
        for (StaticResourceMount mount : staticMounts) {
            if (mount.matches(request.method(), request.path())) {
                if (mount.serve(request)) {
                    return;
                }
                // fallthrough: file not found, continue to route matching
            }
        }
        RouteIndex.RouteMatch match = routes.match(
            request.method(),
            request.path()
        );
        if (match == null) {
            request.send(404, "Not Found");
            return;
        }
        request.pathVariables(match.pathVariables());
        match.handler().handle(request);
    }

    private void handleException(HttpContext ctx, Exception exception) {
        for (ExceptionMapper mapper : mappers) {
            try {
                if (mapper.handle(ctx, exception)) {
                    return;
                }
            } catch (Exception mapperEx) {
                LOG.warn(
                    "Exception mapper {} failed while handling {}",
                    mapper.getClass().getSimpleName(),
                    String.valueOf(exception.getMessage()),
                    mapperEx
                );
            }
        }
        LOG.error(
            "Unhandled exception for {} {}: {}: {}",
            ctx.method(),
            ctx.path(),
            exception.getClass().getSimpleName(),
            String.valueOf(exception.getMessage())
        );
        try {
            ctx.status(500);
            ctx.send(500, "Internal Server Error");
        } catch (Exception sendEx) {
            LOG.error("Failed to send error response", sendEx);
        }
    }

    private void publish(Object event) {
        try {
            container.get(EventBus.class).publish(event);
        } catch (Exception ex) {
            LOG.debug(
                "EventBus publish failed for {}: {}",
                event.getClass().getSimpleName(),
                ex.getMessage()
            );
        }
    }

    private record PreparedFilters(
        List<HttpFilter> filters,
        RequestTimingFilter timingFilter
    ) {}
}
