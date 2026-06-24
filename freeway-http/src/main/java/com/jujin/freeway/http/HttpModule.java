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
            var symbols = container.get(SymbolSource.class);
            var coercer = container.get(Coercer.class);
            boolean enabled = config(symbols, coercer,
                HttpConfigKeys.CORS_ENABLED, HttpConfigKeys.LEGACY_PREFIX + ".cors.enabled", true);
            String origins = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOWED_ORIGINS, HttpConfigKeys.LEGACY_PREFIX + ".cors.allowed-origins", "*");
            String methods = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOWED_METHODS, HttpConfigKeys.LEGACY_PREFIX + ".cors.allowed-methods",
                "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            String headers = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOWED_HEADERS, HttpConfigKeys.LEGACY_PREFIX + ".cors.allowed-headers",
                "Content-Type, Authorization");
            String exposed = config(symbols, coercer,
                HttpConfigKeys.CORS_EXPOSED_HEADERS, HttpConfigKeys.LEGACY_PREFIX + ".cors.exposed-headers", "");
            String maxAge = config(symbols, coercer,
                HttpConfigKeys.CORS_MAX_AGE, HttpConfigKeys.LEGACY_PREFIX + ".cors.max-age", "3600");
            boolean credentials = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOW_CREDENTIALS, HttpConfigKeys.LEGACY_PREFIX + ".cors.allow-credentials", false);
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
            var symbols = container.get(SymbolSource.class);
            var coercer = container.get(Coercer.class);

            String host = config(symbols, coercer,
                HttpConfigKeys.SERVER_HOST, HttpConfigKeys.LEGACY_PREFIX + ".server.host", "127.0.0.1");
            int port = config(symbols, coercer,
                HttpConfigKeys.SERVER_PORT, HttpConfigKeys.LEGACY_PREFIX + ".server.port", 8080);
            int backlog = config(symbols, coercer,
                HttpConfigKeys.SERVER_BACKLOG, HttpConfigKeys.LEGACY_PREFIX + ".server.backlog", 0);
            Duration shutdownGrace = config(symbols, coercer,
                HttpConfigKeys.SERVER_SHUTDOWN_GRACE, HttpConfigKeys.LEGACY_PREFIX + ".server.shutdown-grace",
                Duration.ofSeconds(2));

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
            var symbols = container.get(SymbolSource.class);
            var coercer = container.get(Coercer.class);
            boolean enabled = config(symbols, coercer,
                HttpConfigKeys.HEALTH_ENABLED, HttpConfigKeys.LEGACY_PREFIX + ".health.enabled", true);
            String path = config(symbols, coercer,
                HttpConfigKeys.HEALTH_PATH, HttpConfigKeys.LEGACY_PREFIX + ".health.path", "/healthz");
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
    private static <T> T config(SymbolSource symbols, Coercer coercer, String key, String legacyKey, T defaultValue) {
        String raw = symbols.resolve(key, null);
        if (raw == null && legacyKey != null) {
            raw = symbols.resolve(legacyKey, null);
        }
        if (raw == null) {
            return defaultValue;
        }
        return coercer.coerce(raw, (Class<T>) defaultValue.getClass());
    }
}
