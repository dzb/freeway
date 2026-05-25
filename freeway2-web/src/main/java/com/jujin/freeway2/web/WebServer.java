package com.jujin.freeway2.web;

import com.jujin.freeway2.ioc.AfterRealized;
import com.jujin.freeway2.ioc.Container;
import com.jujin.freeway2.ioc.ServiceId;
import com.jujin.freeway2.ioc.annotation.ExtensionPoint;
import com.jujin.freeway2.ioc.annotation.Value;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WebServer implements AutoCloseable, AfterRealized {
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
    private final WebServerConfig config;
    private final boolean healthEnabled;
    private final String healthPath;
    private final WebRequestHandler requestHandler;

    private volatile WebServerHandle handle;

    public WebServer(
        RouteIndex routes,
        WebSocketIndex websocketIndex,
        CorsFilter corsFilter,
        @ExtensionPoint(StaticResourceMount.class) List<StaticResourceMount> staticMounts,
        @ExtensionPoint(HttpFilter.class) List<HttpFilter> filters,
        @ExtensionPoint(ExceptionMapper.class) List<ExceptionMapper> mappers,
        Container container,
        @Value("${web.engine:robaho}") String webEngineId,
        @Value("${web.server.host:127.0.0.1}") String host,
        @Value("${web.server.port:8080}") int port,
        @Value("${web.server.backlog:0}") int backlog,
        @Value("${web.server.shutdown-grace-seconds:2}") int shutdownGraceSeconds,
        @Value("${web.health.enabled:true}") boolean healthEnabled,
        @Value("${web.health.path:/healthz}") String healthPath
    ) {
        this.routes = Objects.requireNonNull(routes, "routes");
        this.websocketIndex = Objects.requireNonNull(websocketIndex, "websocketIndex");
        this.corsFilter = Objects.requireNonNull(corsFilter, "corsFilter");
        this.staticMounts = List.copyOf(staticMounts == null ? List.of() : staticMounts);
        PreparedFilters preparedFilters = prepareFilters(filters);
        this.filters = List.copyOf(preparedFilters.filters());
        this.timingFilter = preparedFilters.timingFilter();
        this.mappers = List.copyOf(mappers == null ? List.of() : mappers);
        this.container = Objects.requireNonNull(container, "container");
        this.webEngineId = webEngineId;
        this.config = new WebServerConfig(host, port, backlog, shutdownGraceSeconds);
        this.healthEnabled = healthEnabled;
        this.healthPath = normalizeHealthPath(healthPath);
        this.requestHandler = new WebRequestHandler() {
            @Override
            public void handle(HttpContext ctx) throws Exception {
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
                }
            }

            @Override
            public WebSocketMatch websocket(String method, String path, String origin) {
                // validate origin against CORS policy to prevent CSWSH
                String resolvedOrigin = corsFilter.resolveAllowedOrigin(origin);
                if (resolvedOrigin == null && origin != null && !origin.isBlank()) {
                    LOG.warn("WebSocket upgrade rejected: origin '{}' not allowed for {}", origin, path);
                    return null;
                }
                return websocketIndex.match(method, path);
            }
        };
    }

    public String host() {
        return ensureStarted().host();
    }

    public int port() {
        return ensureStarted().port();
    }

    @Override
    public void close() {
        WebServerHandle h = this.handle;
        if (h != null) {
            h.close();
            LOG.info("Freeway2 web server stopped");
        }
    }

    private WebServerHandle ensureStarted() {
        WebServerHandle h = this.handle;
        if (h != null) {
            return h;
        }
        synchronized (this) {
            h = this.handle;
            if (h != null) {
                return h;
            }
            WebEngine engine = resolveEngine(container, webEngineId);
            try {
                h = engine.start(config, requestHandler);
            } catch (IOException ex) {
                throw new RuntimeException("Failed to start web engine " + webEngineId, ex);
            }
            this.handle = h;
            LOG.info("Freeway2 web server started on {}:{}", h.host(), h.port());
            return h;
        }
    }

    @Override
    public void afterRealized() {
        // 在容器 realize 之后（已逃出 computeIfAbsent 锁域）自动启动引擎
        ensureStarted();
    }

    private static WebEngine resolveEngine(Container container, String webEngineId) {
        String engineId = HttpContext.blankToNull(webEngineId);
        if (engineId == null) {
            engineId = "robaho";
        }
        try {
            return container.get(WebEngine.class, ServiceId.of(engineId));
        } catch (RuntimeException ex) {
            if (!"robaho".equals(engineId)) {
                throw new IllegalStateException("Unable to resolve web engine " + engineId, ex);
            }
            LOG.warn("Default engine 'robaho' not found, falling back to any available engine");
            return container.get(WebEngine.class);
        }
    }

    private RouteHandler buildChain(RouteHandler handler, List<HttpFilter> filters) {
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
        List<HttpFilter> normalized = new ArrayList<>();
        RequestTimingFilter timingFilter = null;
        for (HttpFilter filter : filters == null ? List.<HttpFilter>of() : filters) {
            if (filter instanceof RequestTimingFilter timing) {
                if (timingFilter == null) {
                    timingFilter = timing;
                }
            } else {
                normalized.add(filter);
            }
        }
        return new PreparedFilters(normalized, timingFilter != null ? timingFilter : new RequestTimingFilter());
    }

    private void processRequest(HttpContext ctx) throws Exception {
        // inner handler: health check, static resources, or route dispatch
        RouteHandler inner = request -> {
            if (healthEnabled && "GET".equalsIgnoreCase(request.method()) && healthPath.equals(request.path())) {
                request.sendJson(200, java.util.Map.of("status", "ok"));
                return;
            }
            for (StaticResourceMount mount : staticMounts) {
                if (mount.matches(request.method(), request.path())) {
                    mount.serve(request);
                    return;
                }
            }
            RouteIndex.RouteMatch match = routes.match(request.method(), request.path());
            if (match == null) {
                request.send(404, "Not Found");
                return;
            }
            request.pathVariables(match.pathVariables());
            match.handler().handle(request);
        };
        // cors filter + all user-defined filters wrap everything
        RouteHandler chain = buildChain(inner, this.filters);
        corsFilter.doFilter(ctx, chain);
    }

    private static String normalizeHealthPath(String healthPath) {
        String path = HttpContext.blankToNull(healthPath);
        if (path == null) {
            return "/healthz";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private void handleException(HttpContext ctx, Exception exception) {
        for (ExceptionMapper mapper : mappers) {
            try {
                if (mapper.handle(ctx, exception)) {
                    return;
                }
            } catch (Exception mapperEx) {
                LOG.warn("Exception mapper {} failed while handling {}", mapper.getClass().getSimpleName(), exception.getMessage(), mapperEx);
            }
        }
        LOG.error("Unhandled exception for {} {}: {}: {}", ctx.method(), ctx.path(), exception.getClass().getSimpleName(), exception.getMessage());
        try {
            ctx.status(500);
            ctx.send(500, "Internal Server Error");
        } catch (Exception sendEx) {
            LOG.error("Failed to send error response", sendEx);
        }
    }

    private record PreparedFilters(List<HttpFilter> filters, RequestTimingFilter timingFilter) {
    }
}
