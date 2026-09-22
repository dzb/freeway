package com.jujin.freeway.http;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.SslContexts;
import com.jujin.freeway.http.SslSettings;
import com.jujin.freeway.http.internal.SslReloader;
import com.jujin.freeway.http.filter.AccessLogFilter;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ErrorHandler;
import com.jujin.freeway.http.filter.HealthCheck;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.route.LazyHandler;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteGroup;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.staticfile.StaticResourceMount;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

@Marker(Builtin.class)
/** Freeway HTTP module: wires routes, filters, engine, WebSocket, SSE, and the server runtime hook. */
public final class HttpModule implements ModuleEx {
    private static final Logger LOG = LoggerFactory.getLogger(HttpModule.class);
    public static final String SERVER_HOOK = "freeway.http.server";

    /** The one key this module reads on its own rather than through a value type:
     *  the access log is a filter the module contributes, not a field of anything. */
    private static final SymbolSpec<Boolean> ACCESS_LOG_ENABLED =
        SymbolSpec.of(HttpConfigKeys.ACCESS_LOG_ENABLED, Boolean.class, false);
    private volatile SslReloader sslReloader;

    @Override
    public void bind(Binder binder) {
        binder.bind(RouteIndex.class).to(container -> {
            var routes = new ArrayList<>(container.extension(Route.class).all());
            for (var r : routes) {
                resolveLazy(r, container);
            }
            // Resolve LazyHandlers from RouteGroup-expanded routes too
            var allRoutes = new ArrayList<>(routes);
            for (RouteGroup group : container.extension(RouteGroup.class).all()) {
                for (Route expanded : group.expand()) {
                    resolveLazy(expanded, container);
                    allRoutes.add(expanded);
                }
            }
            return new RouteIndex(allRoutes, List.of());
        });
        binder.bind(WebSocketIndex.class).to(WebSocketIndex.class);
        binder.bind(JsonCodec.class).to(JsonCodecDefault.class);

        // Config — each face resolved by the type that owns its keys and its
        // defaults (HttpServerConfig.from, CorsFilter.from, HealthFilter.from,
        // SslSettings.from): the module reads no defaults of its own, so a key
        // and a default cannot be stated in two places and drift.
        binder.bind(SslSettings.class).to(container ->
            SslSettings.from(container.get(SymbolSource.class)));
        binder.bind(CorsFilter.class).to(container ->
            CorsFilter.from(container.get(SymbolSource.class)));

        // Engines — concrete bindings, HTTPS when SSL is enabled
        binder.bind(FreewayHttpEngine.class).to(container -> {
            var json = container.get(JsonCodec.class);
            var coercer = container.get(Coercer.class);
            var metrics = container.get(Metrics.class);

            SslSettings ssl = container.get(SslSettings.class);
            if (!ssl.enabled()) {
                LOG.debug("SSL disabled, using plain HTTP engine");
                return new FreewayHttpEngine(
                    FreewayHttpEngine.Wiring.defaults(json, coercer).withMetrics(metrics));
            }

            LOG.info("Initializing HTTPS engine from keystore {} (type={}, http2={}, clientAuth={})",
                ssl.keyStorePath(), ssl.keyStoreType(), ssl.http2(), ssl.clientAuth());
            SSLContext sslContext = SslContexts.build(ssl);
            SSLParameters sslParameters = SslContexts.parameters(ssl);
            LOG.info("HTTPS engine initialized — TLS via JDK SSLContext");
            return new FreewayHttpEngine(
                FreewayHttpEngine.Wiring.defaults(json, coercer)
                    .withSsl(sslContext, ssl.http2())
                    .withSslParameters(sslParameters)
                    .withMetrics(metrics));
        });

        // HttpEngine — bind to FreewayHttpEngine. Extension modules bind their
        // engine (e.g. Undertow/Jetty adapters) with a distinct id + primary();
        // the Builtin marker is what the server hook probes to decide whether
        // the built-in engine — the only one with reload(SSLContext) — is the
        // engine actually serving.
        binder.bind(HttpEngine.class).to(container ->
            container.get(FreewayHttpEngine.class)).id("builtin")
            .marker(Builtin.class);

        // Engine contract — one binding, so a caller that must vary a transport
        // knob (an adapter's own defaults, a test on an ephemeral port) overrides
        // it with .primary() instead of assembling a second server.
        binder.bind(HttpServerConfig.class).to(container ->
            HttpServerConfig.from(container.get(SymbolSource.class))).id("builtin");

        // WebServer — the module's whole job: read the parts off the container,
        // hand them to the one derivation. No policy of its own; see create(...).
        binder.bind(WebServer.class).to(container -> {
            var filters = new ArrayList<>(
                container.extension(HttpFilter.class).all());
            if (container.get(SymbolSource.class)
                    .resolve(ACCESS_LOG_ENABLED)) {
                filters.add(new AccessLogFilter());
            }
            var components = new RequestComponents(
                container.get(RouteIndex.class),
                container.get(WebSocketIndex.class),
                container.get(CorsFilter.class),
                container.get(HealthFilter.class),
                container.extension(StaticResourceMount.class).all(),
                List.copyOf(filters),
                container.extension(ErrorHandler.class).all()
            );
            return WebServer.create(
                container.get(HttpEngine.class),
                container.get(HttpServerConfig.class),
                components,
                event -> container.get(EventBus.class).publish(event));
        });

        binder.contribute(RuntimeHook.class).add(SERVER_HOOK, new RuntimeHook() {
            @Override
            public void start(Container container) {
                SslSettings ssl = container.get(SslSettings.class);
                container.get(WebServer.class).start();
                if (ssl.enabled() && ssl.reloadInterval() != null
                        && !ssl.reloadInterval().isZero()) {
                    if (isBuiltinEngineActive(container)) {
                        // The built-in engine is the one WebServer started, so
                        // reloading its SSLContext actually rotates the live
                        // server's certificate material.
                        sslReloader = new SslReloader(
                            container.get(FreewayHttpEngine.class),
                            Path.of(ssl.keyStorePath()),
                            ssl.trustStorePath() != null
                                ? Path.of(ssl.trustStorePath()) : null,
                            ssl.sniDirectory() != null
                                ? Path.of(ssl.sniDirectory()) : null,
                            ssl.reloadInterval(),
                            () -> SslContexts.build(ssl));
                        try {
                            sslReloader.start();
                        } catch (RuntimeException ex) {
                            sslReloader.close();
                            sslReloader = null;
                            container.get(WebServer.class).stop();
                            throw ex;
                        }
                    } else {
                        LOG.info("TLS hot reload skipped: the active HttpEngine is not "
                            + "the built-in FreewayHttpEngine — rotating certificate "
                            + "material is the active engine module's responsibility");
                    }
                }
            }

            @Override
            public void stop(Container container) {
                if (sslReloader != null) {
                    sslReloader.close();
                    sslReloader = null;
                }
                container.get(WebServer.class).stop();
            }
        });

        binder.bind(HealthCheck.class).to(container -> HealthCheck.ALWAYS_OK);
        // The check is a bound service (a container question), the path and the
        // switch are keys — from(...) takes both and states neither twice.
        binder.bind(HealthFilter.class).to(container -> HealthFilter.from(
            container.get(SymbolSource.class), container.get(HealthCheck.class)));
    }

    /** Resolves a {@link LazyHandler} (class-based route) against the
     *  container, which instantiates the handler class with constructor
     *  injection. */
    private static void resolveLazy(Route r, Container c) {
        if (r.handler() instanceof LazyHandler lh) lh.resolve(c);
    }

    /**
     * True when the HttpEngine the container resolves ({@code primary()}
     * wins over the built-in binding) is this module's built-in binding —
     * i.e. the engine WebServer started is the {@code FreewayHttpEngine}
     * whose {@code reload(SSLContext)} the {@link SslReloader} drives.
     * Probed through the binding's marker (no instance realization), so an
     * ext engine module selected via {@code .primary()} answers false
     * without ever constructing the built-in engine.
     */
    static boolean isBuiltinEngineActive(Container container) {
        return container.isActiveBinding(HttpEngine.class, Builtin.class);
    }

}
