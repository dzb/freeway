package com.jujin.freeway.http;

import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.annotation.Value;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

public final class WebServer implements AutoCloseable {
    private static final Logger LOG = com.jujin.freeway.commons.logging.LoggingBootstrap.logger(WebServer.class);

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
    private final boolean healthEnabled;
    private final String healthPath;
    private final HttpRequestHandler requestHandler;

    private volatile HttpServerHandle handle;

    public WebServer(
        RouteIndex routes,
        WebSocketIndex websocketIndex,
        CorsFilter corsFilter,
        StaticResourceMounts staticMounts,
        HttpFilters filters,
        ExceptionMappers mappers,
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
        this.staticMounts = staticMounts.all();
        PreparedFilters preparedFilters = prepareFilters(filters.all());
        this.filters = List.copyOf(preparedFilters.filters());
        this.timingFilter = preparedFilters.timingFilter();
        this.mappers = mappers.all();
        this.container = Objects.requireNonNull(container, "container");
        this.webEngineId = webEngineId;
        this.config = new HttpServerConfig(host, port, backlog, shutdownGraceSeconds);
        this.healthEnabled = healthEnabled;
        this.healthPath = normalizeHealthPath(healthPath);
        this.requestHandler = new HttpRequestHandler() {
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
                String allowed = corsFilter.resolveAllowedOrigin(origin);
                if (allowed == null && origin != null && !origin.isBlank()) {
                    LOG.warn("WebSocket upgrade rejected: origin '{}' not allowed for {}", origin, path);
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

    public boolean running() {
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
                throw new RuntimeException("Failed to start web engine " + webEngineId, ex);
            }
            awaitReady(h.host(), h.port());
            this.handle = h;
            LOG.info("Freeway web server started on {}:{}", h.host(), h.port());
            return h;
        }
    }

    private static void awaitReady(String host, int port) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket(host, port)) {
                s.setSoTimeout(1000);
                s.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                s.getOutputStream().flush();
                byte[] buf = new byte[12];
                int read = s.getInputStream().read(buf);
                if (read >= 5) {
                    String response = new String(buf, 0, read, StandardCharsets.US_ASCII);
                    if (response.startsWith("HTTP/")) {
                        return;
                    }
                }
            } catch (IOException ignored) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static HttpEngine resolveEngine(Container container, String webEngineId) {
        String engineId = HttpContext.blankToNull(webEngineId);
        if (engineId == null) {
            engineId = "robaho";
        }
        try {
            return container.get(HttpEngine.class, engineId);
        } catch (RuntimeException ex) {
            if (!"robaho".equals(engineId)) {
                throw new IllegalStateException("Unable to resolve web engine " + engineId, ex);
            }
            LOG.warn("Default engine 'robaho' not found, falling back to built-in JDK engine");
            return container.get(HttpEngine.class, "jdk");
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
        List<HttpFilter> items = new ArrayList<>();
        RequestTimingFilter timing = null;
        for (HttpFilter filter : filters == null ? List.<HttpFilter>of() : filters) {
            if (filter instanceof RequestTimingFilter candidate) {
                if (timing == null) {
                    timing = candidate;
                }
            } else {
                items.add(filter);
            }
        }
        return new PreparedFilters(items, timing != null ? timing : new RequestTimingFilter());
    }

    private void processRequest(HttpContext ctx) throws Exception {
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
