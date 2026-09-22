package com.jujin.freeway.http;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
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
    // The built-in engine's own knobs: read here (the module owns the keys),
    // pushed into the engine's Wiring below — HttpServerConfig does not carry
    // fields a third-party engine cannot apply.
    private static final SymbolSpec<Integer> H2_RESET_BURST_LIMIT =
        SymbolSpec.of(HttpConfigKeys.H2_RESET_BURST_LIMIT, Integer.class,
            FreewayHttpEngine.DEFAULT_H2_RESET_BURST_LIMIT);
    private static final SymbolSpec<Duration> H2_RESET_WINDOW =
        SymbolSpec.of(HttpConfigKeys.H2_RESET_WINDOW, Duration.class,
            FreewayHttpEngine.DEFAULT_H2_RESET_WINDOW);

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
            var symbols = container.get(SymbolSource.class);
            int h2Burst = symbols.resolve(H2_RESET_BURST_LIMIT);
            Duration h2Window = symbols.resolve(H2_RESET_WINDOW);

            SslSettings ssl = container.get(SslSettings.class);
            if (!ssl.enabled()) {
                LOG.debug("SSL disabled, using plain HTTP engine");
                return new FreewayHttpEngine(
                    FreewayHttpEngine.Wiring.defaults(json, coercer)
                        .withMetrics(metrics)
                        .withH2Reset(h2Burst, h2Window));
            }

            LOG.info("Initializing HTTPS engine from keystore {} (type={}, http2={}, clientAuth={})",
                ssl.keyStorePath(), ssl.keyStoreType(), ssl.http2(), ssl.clientAuth());
            SSLContext sslContext = SslContexts.build(ssl);
            SSLParameters sslParameters = SslContexts.parameters(ssl);
            LOG.info("HTTPS engine initialized — TLS via JDK SSLContext");
            var wiring = FreewayHttpEngine.Wiring.defaults(json, coercer)
                .withSsl(sslContext, ssl.http2())
                .withSslParameters(sslParameters)
                .withMetrics(metrics)
                .withH2Reset(h2Burst, h2Window);
            if (ssl.reloadInterval() != null && !ssl.reloadInterval().isZero()) {
                wiring = wiring.withSslReload(new FreewayHttpEngine.Wiring.SslReload(
                    Path.of(ssl.keyStorePath()),
                    ssl.trustStorePath() != null ? Path.of(ssl.trustStorePath()) : null,
                    ssl.sniDirectory() != null ? Path.of(ssl.sniDirectory()) : null,
                    ssl.reloadInterval(),
                    () -> SslContexts.build(ssl)));
            }
            return new FreewayHttpEngine(wiring);
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

        // HttpServer — the module's whole job: read the parts off the container,
        // hand them to the one derivation. No policy of its own; see create(...).
        binder.bind(HttpServer.class).to(container -> {
            var filters = new ArrayList<>(
                container.extension(HttpFilter.class).all());
            if (container.get(SymbolSource.class)
                    .resolve(ACCESS_LOG_ENABLED)) {
                filters.add(new AccessLogFilter());
            }
            var pipeline = new HttpPipeline(
                container.get(RouteIndex.class),
                container.get(WebSocketIndex.class),
                container.get(CorsFilter.class),
                container.get(HealthFilter.class),
                container.extension(StaticResourceMount.class).all(),
                List.copyOf(filters),
                container.extension(ErrorHandler.class).all()
            );
            return HttpServer.create(
                container.get(HttpEngine.class),
                container.get(HttpServerConfig.class),
                pipeline,
                event -> container.get(EventBus.class).publish(event));
        });

        binder.contribute(RuntimeHook.class).add(SERVER_HOOK, new RuntimeHook() {
            @Override
            public void start(Container container) {
                reportRetiredPrefixKeys(container.get(SymbolSource.class));
                SslSettings ssl = container.get(SslSettings.class);
                boolean builtinEngine = isBuiltinEngineActive(container);
                if (!builtinEngine) {
                    reportIgnoredH2Guard(container.get(SymbolSource.class));
                }
                container.get(HttpServer.class).start();
                // The built-in engine starts its own reloader inside
                // HttpServer.start() (Wiring.SslReload rides the engine), so
                // only the non-built-in case needs telling here.
                if (!builtinEngine && ssl.enabled() && ssl.reloadInterval() != null
                        && !ssl.reloadInterval().isZero()) {
                    LOG.info("TLS hot reload skipped: the active HttpEngine is not "
                        + "the built-in FreewayHttpEngine — rotating certificate "
                        + "material is the active engine module's responsibility");
                }
            }

            @Override
            public void stop(Container container) {
                // The engine's handle closes its own reloader while stopping.
                container.get(HttpServer.class).stop();
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
     * i.e. the engine HttpServer started is the {@code FreewayHttpEngine}
     * that drives its own certificate hot reload from {@code Wiring.SslReload}.
     * Probed through the binding's marker (no instance realization), so an
     * ext engine module selected via {@code .primary()} answers false
     * without ever constructing the built-in engine.
     */
    static boolean isBuiltinEngineActive(Container container) {
        return container.isActiveBinding(HttpEngine.class, Builtin.class);
    }

    /**
     * Loudness for keys that stopped taking effect: {@code freeway.http.h2.*}
     * guards only the built-in engine, so a tuned value under a third-party
     * engine would otherwise be silently ignored. Default-valued keys are
     * harmless to ignore (the guard is unchanged) and not reported — only a
     * tuned value is, naming where the key now lives.
     */
    private static void reportIgnoredH2Guard(SymbolSource symbols) {
        int burst = symbols.resolve(H2_RESET_BURST_LIMIT);
        Duration window = symbols.resolve(H2_RESET_WINDOW);
        if (burst != FreewayHttpEngine.DEFAULT_H2_RESET_BURST_LIMIT
                || !FreewayHttpEngine.DEFAULT_H2_RESET_WINDOW.equals(window)) {
            LOG.warn("freeway.http.h2.* guards only the built-in FreewayHttpEngine and the "
                + "active HttpEngine is not built in — the tuned value has no effect here; "
                + "the active engine module owns its own guard (built-in path: HttpModule "
                + "wires these keys into FreewayHttpEngine.Wiring automatically)");
        }
    }

    /**
     * Loudness for configuration that stopped taking effect: v1.2.1 read
     * these keys under {@value HttpConfigKeys#RETIRED_PREFIX}, v1.2.2 renamed
     * the prefix to {@code freeway.http.*} and the fallback was later removed
     * — an app still configured with the old prefix runs silently on
     * defaults. The dead shape is probed precisely: the retired twin present
     * while the current key is <em>absent</em>. A present current key means
     * its value reaches the server no matter where it came from (literal or
     * a {@code ${freeway.web.*}} reference), so only the silent case warns.
     *
     * <p>The key list is enumerated off the constants class itself, so the
     * notice cannot drift from the key table: a key added tomorrow gets its
     * twin probed without a second list to maintain (its twin can only be
     * absent, which costs one lookup). A probe whose value fails to expand
     * counts as present — a dead key's broken content must not stop startup
     * before the notice names it (a present <em>current</em> key is parsed
     * later by its value type and fails there, naming itself).
     *
     * @return {@code "old → new"} entries; empty when nothing is dead
     */
    static List<String> retiredPrefixNotices(SymbolSource symbols) {
        var dead = new ArrayList<String>();
        for (Field field : HttpConfigKeys.class.getFields()) {
            if (field.getType() != String.class) continue;
            String key;
            try {
                key = (String) field.get(null);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            if (key == null || !key.startsWith(HttpConfigKeys.PREFIX)) continue;
            String retired = HttpConfigKeys.RETIRED_PREFIX
                + key.substring(HttpConfigKeys.PREFIX.length());
            if (present(symbols, retired) && !present(symbols, key)) {
                dead.add(retired + " → " + key);
            }
        }
        return dead;
    }

    /**
     * Presence, not value: a key nothing reads still counts as configured
     * when its value cannot expand, and that failure may not propagate —
     * startup must reach the notice naming the rename.
     */
    private static boolean present(SymbolSource symbols, String key) {
        try {
            return symbols.resolve(key, null) != null;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** One WARN at startup listing every dead retired-prefix key and its
     *  current name — the fix travels with the report. */
    private static void reportRetiredPrefixKeys(SymbolSource symbols) {
        var dead = retiredPrefixNotices(symbols);
        if (!dead.isEmpty()) {
            LOG.warn("config key(s) under the retired '{}' prefix are no longer read "
                + "(the prefix became '{}' in v1.2.2): {} — rename each key as shown",
                HttpConfigKeys.RETIRED_PREFIX, HttpConfigKeys.PREFIX,
                String.join(", ", dead));
        }
    }

}
