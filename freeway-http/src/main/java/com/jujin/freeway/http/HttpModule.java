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
            boolean enabled = boolConfig(container, "freeway.web.cors.enabled", true);
            String origins = stringConfig(container, "freeway.web.cors.allowed-origins", "*");
            String methods = stringConfig(container, "freeway.web.cors.allowed-methods",
                "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            String headers = stringConfig(container, "freeway.web.cors.allowed-headers",
                "Content-Type, Authorization");
            String exposed = stringConfig(container, "freeway.web.cors.exposed-headers", "");
            String maxAge = stringConfig(container, "freeway.web.cors.max-age", "3600");
            boolean credentials = boolConfig(container, "freeway.web.cors.allow-credentials", false);
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
            container.get(FreewayHttpEngine.class)).id("freeway");

        // WebServer — bridge IoC capabilities to plain constructor
        binder.bind(WebServer.class).to(container -> {
            HttpEngine engine = container.get(HttpEngine.class);

            String host = stringConfig(container, "freeway.web.server.host", "127.0.0.1");
            int port = intConfig(container, "freeway.web.server.port", 8080);
            int backlog = intConfig(container, "freeway.web.server.backlog", 0);
            Duration shutdownGrace = durationConfig(container,
                "freeway.web.server.shutdown-grace", Duration.ofSeconds(2));

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
            boolean enabled = boolConfig(container, "freeway.web.health.enabled", true);
            String path = stringConfig(container, "freeway.web.health.path", "/healthz");
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

    private static String stringConfig(Container container, String key, String defaultValue) {
        return container.get(SymbolSource.class).resolve(key, defaultValue);
    }

    private static boolean boolConfig(Container container, String key, boolean defaultValue) {
        String value = stringConfig(container, key, null);
        if (value == null) return defaultValue;
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    private static int intConfig(Container container, String key, int defaultValue) {
        String value = stringConfig(container, key, null);
        if (value == null) return defaultValue;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static Duration durationConfig(Container container, String key,
                                           Duration defaultValue) {
        String value = stringConfig(container, key, null);
        if (value == null) return defaultValue;
        // Support "2s", "500ms", "1m" suffixes
        value = value.trim();
        try {
            if (value.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(
                    value.substring(0, value.length() - 2)));
            }
            if (value.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(
                    value.substring(0, value.length() - 1)));
            }
            if (value.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(
                    value.substring(0, value.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
