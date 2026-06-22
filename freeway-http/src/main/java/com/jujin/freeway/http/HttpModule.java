package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.HealthCheck;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.filter.RequestTimingFilter;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.Module2;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

public final class HttpModule implements Module2 {
    public static final String SERVER_HOOK = "freeway.http.server";

    @Override
    public void bind(Binder binder) {
        binder.bind(RouteIndex.class).to(RouteIndex.class);
        binder.bind(WebSocketIndex.class).to(WebSocketIndex.class);
        binder.bind(JsonCodec.class).to(JsonCodecDefault.class);

        // CorsFilter — bridge ioC config to plain constructor
        binder.bind(CorsFilter.class).to(container -> {
            boolean enabled = config(container, HttpConfigKeys.CORS_ENABLED, true);
            String origins = config(container, HttpConfigKeys.CORS_ALLOWED_ORIGINS, "*");
            String methods = config(container, HttpConfigKeys.CORS_ALLOWED_METHODS,
                "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            String headers = config(container, HttpConfigKeys.CORS_ALLOWED_HEADERS,
                "Content-Type, Authorization");
            String exposed = config(container, HttpConfigKeys.CORS_EXPOSED_HEADERS, "");
            String maxAge = config(container, HttpConfigKeys.CORS_MAX_AGE, "3600");
            boolean credentials = config(container, HttpConfigKeys.CORS_ALLOW_CREDENTIALS, false);
            return new CorsFilter(enabled, origins, methods, headers,
                exposed.isBlank() ? null : exposed, maxAge, credentials);
        });

        // Engines — concrete bindings
        binder.bind(FreewayHttpEngine.class).to(container -> {
            var json = container.get(JsonCodec.class);
            var coercer = container.get(Coercer.class);
            return new FreewayHttpEngine(json, coercer);
        });

        // HttpEngine — bind to FreewayHttpEngine
        binder.bind(HttpEngine.class).to(container ->
            container.get(FreewayHttpEngine.class)).id("builtin");

        // WebServer — bridge IoC capabilities to plain constructor
        binder.bind(WebServer.class).to(container -> {
            HttpEngine engine = container.get(HttpEngine.class);

            String host = config(container, HttpConfigKeys.SERVER_HOST, "127.0.0.1");
            int port = config(container, HttpConfigKeys.SERVER_PORT, 8080);
            int backlog = config(container, HttpConfigKeys.SERVER_BACKLOG, 0);
            Duration shutdownGrace = config(container,
                HttpConfigKeys.SERVER_SHUTDOWN_GRACE, Duration.ofSeconds(2));

            Consumer<Object> eventSink = event ->
                container.get(EventBus.class).publish(event);

            var pipeline = new RequestPipeline(
                container.get(RouteIndex.class),
                container.get(WebSocketIndex.class),
                container.get(CorsFilter.class),
                container.get(HealthFilter.class),
                container.extension(StaticResourceMount.class).all(),
                container.extension(HttpFilter.class).all(),
                container.extension(ExceptionMapper.class).all()
            );

            return new WebServer(
                engine,
                new HttpServerConfig(host, port, backlog, shutdownGrace),
                eventSink,
                pipeline
            );
        });

        binder.contribute(RuntimeHook.class).add(SERVER_HOOK, new RuntimeHook() {
            @Override
            public void start(Container container) {
                container.get(WebServer.class).start();
            }

            @Override
            public void stop(Container container) {
                container.get(WebServer.class).stop();
            }
        });

        binder.bind(HealthCheck.class).to(HealthCheck.Default.class);
        binder.bind(HealthFilter.class).to(container -> {
            boolean enabled = config(container, HttpConfigKeys.HEALTH_ENABLED, true);
            String path = config(container, HttpConfigKeys.HEALTH_PATH, "/healthz");
            HealthCheck check = container.get(HealthCheck.class);
            return new HealthFilter(enabled, path, check);
        });

        binder.contribute(HttpFilter.class).add(new RequestTimingFilter());

        binder.contribute(ExceptionMapper.class).add((ctx, ex) -> {
            if (ex instanceof BodyTooLargeException) {
                ctx.sendJson(HttpStatus.PAYLOAD_TOO_LARGE, Map.of(
                    "error", "Payload Too Large",
                    "message", ex.getMessage()
                ));
                return true;
            }
            if (ex instanceof ValidationException ve) {
                var errors = ve.result().getErrors().stream()
                        .map(e -> Map.of("field", e.field(), "message", e.message()))
                    .toList();
                ctx.sendJson(400, Map.of(
                    "error", "Validation Failed",
                    "details", errors
                ));
                return true;
            }
            return false;
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T config(Container container, String key, T defaultValue) {
        String raw = container.get(SymbolSource.class).resolve(key, null);
        if (raw == null) {
            return defaultValue;
        }
        return container.get(Coercer.class).coerce(raw, (Class<T>) defaultValue.getClass());
    }
}
