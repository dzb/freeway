package com.jujin.freeway.http;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
import com.jujin.freeway.http.websocket.LazyEndpoint;
import com.jujin.freeway.http.websocket.WebSocketGroup;
import com.jujin.freeway.http.websocket.WebSocketIndex;
import com.jujin.freeway.http.websocket.WebSocketRoute;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.event.EventBus;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;
import com.jujin.freeway.ioc.annotation.Builtin;
import com.jujin.freeway.ioc.annotation.Marker;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

@Marker(Builtin.class)
/** Freeway HTTP module: wires routes, filters, engine, WebSocket, SSE, and the server runtime hook. */
public final class HttpModule implements ModuleEx {
    private static final Logger LOG = LoggerFactory.getLogger(HttpModule.class);
    public static final String SERVER_HOOK = "freeway.http.server";

    // Read here rather than through a value type: the access log is a filter
    // the module contributes, not a field of anything — no value type owns it.
    private static final SymbolSpec<Boolean> ACCESS_LOG_ENABLED =
        SymbolSpec.of(ConfigKeys.ACCESS_LOG_ENABLED, Boolean.class, false);
    // The built-in engine's own knobs: also read here (the module owns the
    // keys), pushed into the engine's Wiring below — HttpServerConfig does not
    // carry fields a third-party engine cannot apply.
    private static final SymbolSpec<Integer> H2_RESET_BURST_LIMIT =
        SymbolSpec.of(ConfigKeys.H2_RESET_BURST_LIMIT, Integer.class,
            FreewayHttpEngine.DEFAULT_H2_RESET_BURST_LIMIT);
    private static final SymbolSpec<Duration> H2_RESET_WINDOW =
        SymbolSpec.of(ConfigKeys.H2_RESET_WINDOW, Duration.class,
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
        binder.bind(WebSocketIndex.class).to(container -> {
            var routes = container.extension(WebSocketRoute.class).all();
            var groups = container.extension(WebSocketGroup.class).all();
            for (var r : routes) {
                resolveEndpoint(r, container);
            }
            // Resolve LazyEndpoints from group-expanded routes too
            for (WebSocketGroup group : groups) {
                for (WebSocketRoute expanded : group.expand()) {
                    resolveEndpoint(expanded, container);
                }
            }
            return new WebSocketIndex(routes, groups);
        });
        binder.bind(JsonCodec.class).to(JsonCodecDefault.class);

        // Declared vocabulary for the unknown-key check.
        binder.contribute(KnownKeys.class).add(KnownKeys.of(ConfigKeys.class, ConfigKeys.PREFIX));
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

    /** Resolves a {@link LazyHandler} (class-based route): the container
     *  instantiates the handler class with constructor injection and the
     *  instance is handed to the wrapper — the route package stays free of
     *  container types. */
    private static void resolveLazy(Route r, Container c) {
        if (r.handler() instanceof LazyHandler lh) {
            lh.resolve(() -> c.create(lh.handlerType()));
        }
    }

    /** Resolves a {@link LazyEndpoint} (class-based WebSocket route): the
     *  container instantiates the endpoint class with constructor injection
     *  and the instance is handed to the wrapper — the websocket package
     *  stays free of container types. */
    private static void resolveEndpoint(WebSocketRoute r, Container c) {
        if (r.endpoint() instanceof LazyEndpoint le) {
            le.resolve(() -> c.create(le.endpointType()));
        }
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
     * these keys under {@value ConfigKeys#RETIRED_PREFIX}, v1.2.2 renamed
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
        for (Field field : ConfigKeys.class.getFields()) {
            if (field.getType() != String.class) continue;
            String key;
            try {
                key = (String) field.get(null);
            } catch (ReflectiveOperationException e) {
                continue;
            }
            if (key == null || !key.startsWith(ConfigKeys.PREFIX)) continue;
            String retired = ConfigKeys.RETIRED_PREFIX
                + key.substring(ConfigKeys.PREFIX.length());
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
                ConfigKeys.RETIRED_PREFIX, ConfigKeys.PREFIX,
                String.join(", ", dead));
        }
    }


    /**
     * Configuration keys for the HTTP module.
     * All keys share the {@code freeway.http} namespace.
     *
     * <p><b>The surface has three tiers, and only the first asks for a decision.</b>
     * The grouping below is that classification, not a topic list:
     * <ol>
     *   <li><b>Decision keys</b> — where to listen and how to secure it:
     *       {@link #SERVER_HOST}, {@link #SERVER_PORT}, {@link #SSL_KEY_STORE} with
     *       its password (HTTPS is presence-driven), and
     *       {@link #CORS_ALLOWED_ORIGINS} (the permissive {@code *} default is for
     *       development; a deployment should name its origins). Five keys, and a
     *       service with no TLS and no browser clients needs two of them.</li>
     *   <li><b>Default-optimal switches</b> — one flag per feature (compression,
     *       access log, CORS, health). The default is the recommended posture; the
     *       key exists so a deployment can turn the feature off, not because it
     *       needs a decision. {@link #SSL_ENABLED} is the one tri-state here: unset
     *       defers to keystore presence, and {@code false} is a kill switch.</li>
     *   <li><b>Advanced (rare)</b> — operational tuning whose default is the
     *       value the framework recommends: socket backlog/buffers/timeouts, the
     *       HTTP/2 reset guard, the compression threshold, the body-size ceiling,
     *       the remaining CORS response details, and the TLS protocol/cipher/SNI/
     *       trust-store selection. Some of these default to the platform/JDK value
     *       ({@code 0}, empty); others default to a tuned number. Either way, set
     *       one only when you know why.</li>
     * </ol>
     *
     * <p>There is deliberately no aggregate switch on top of the advanced tier:
     * every cluster already has its flag ({@code compression.enabled},
     * {@code health.enabled}, a {@code 0}/empty "off" in the tuning keys), and a
     * second way to say the same thing is the duplication this catalog avoids.
     */
    public static final class ConfigKeys {
        private ConfigKeys() {}

        public static final String PREFIX = "freeway.http";
        /** The v1.2.1 prefix, retired in v1.2.2 when every key moved to
         * {@code freeway.http.*} (the legacy fallback was removed in e37ba527).
         * Nothing reads keys under it — held solely for the startup notice that
         * names the rename (see {@code HttpModule.retiredPrefixNotices}). */
        static final String RETIRED_PREFIX = "freeway.web";

        // ── Decision keys ─────────────────────────────────────────

        /** Bind address (default {@code 127.0.0.1}; a container binds {@code 0.0.0.0}). */
        public static final String SERVER_HOST           = "freeway.http.server.host";
        /** Listen port (default 8080; {@code 0} = system-assigned). */
        public static final String SERVER_PORT           = "freeway.http.server.port";
        /** Path to the keystore file (PKCS12 or JKS). Presence alone activates
         *  HTTPS (unless {@code ssl.enabled=false} suppresses it). */
        public static final String SSL_KEY_STORE           = "freeway.http.ssl.key-store";
        /** Password for the keystore file. */
        public static final String SSL_KEY_STORE_PASSWORD  = "freeway.http.ssl.key-store-password";
        /** Allowed origins. The default {@code *} is a development posture —
         *  name the real origins in a deployment (see the CORS block below). */
        public static final String CORS_ALLOWED_ORIGINS   = "freeway.http.cors.allowed-origins";

        // ── Default-optimal switches ──────────────────────────────

        /** Max request body size in bytes (default 10MB). A framework policy, not
         *  a platform default: a service accepting uploads must decide this, or a
         *  large upload is rejected with 413. */
        public static final String MAX_BODY_SIZE = "freeway.http.max-body-size";


        /** Master switch — presence-driven: an explicit value wins ({@code true}
         *  on, {@code false} = kill switch suppressing a configured keystore);
         *  unset falls to keystore presence — a configured keystore is an HTTPS
         *  server, nothing set is plaintext. */
        public static final String SSL_ENABLED             = "freeway.http.ssl.enabled";
        /** gzip response compression for compressible content (default true). */
        public static final String COMPRESSION_ENABLED   = "freeway.http.compression.enabled";
        /** Text access log to stdout (default false). */
        public static final String ACCESS_LOG_ENABLED    = "freeway.http.access-log.enabled";
        /** CORS filter on (default true); {@code false} serves no CORS headers. */
        public static final String CORS_ENABLED           = "freeway.http.cors.enabled";
        /** Built-in {@code GET /healthz} endpoint on (default true). */
        public static final String HEALTH_ENABLED = "freeway.http.health.enabled";

        // ── Advanced: server tuning (default = platform default) ──

        /** Accept queue size (default 0 = OS default). */
        public static final String SERVER_BACKLOG        = "freeway.http.server.backlog";
        /** Grace period for in-flight requests on shutdown (default 2s). */
        public static final String SERVER_SHUTDOWN_GRACE = "freeway.http.server.shutdown-grace";
        /** Socket read idle timeout (default 30s; 0 disables). */
        public static final String SERVER_READ_TIMEOUT   = "freeway.http.server.read-timeout";
        /** Per-socket-write timeout (default 30s; 0 disables). */
        public static final String SERVER_WRITE_TIMEOUT  = "freeway.http.server.write-timeout";
        /** Maximum concurrent connections (default 0 = unlimited). */
        public static final String SERVER_MAX_CONNECTIONS = "freeway.http.server.max-connections";
        /** Desired SO_RCVBUF for accepted sockets (default 0 = OS default). */
        public static final String SERVER_RECEIVE_BUFFER = "freeway.http.server.receive-buffer-size";
        /** Desired SO_SNDBUF for accepted sockets (default 0 = OS default). */
        public static final String SERVER_SEND_BUFFER    = "freeway.http.server.send-buffer-size";
        /** Inbound RST_STREAM burst guard: cancels arriving before the server
         *  responded, beyond this count within the reset window, trip the
         *  connection with GOAWAY(ENHANCE_YOUR_CALM) (0 disables the guard).
         *  Engine-private: {@code HttpModule} reads it into the built-in engine's
         *  {@code Wiring}; a third-party engine reports a non-default at startup. */
        public static final String H2_RESET_BURST_LIMIT = "freeway.http.h2.reset-burst-limit";
        /** Sliding window for the reset burst guard (default 10s). A no-op while
         *  {@link #H2_RESET_BURST_LIMIT} is 0 — the guard returns before reading it. */
        public static final String H2_RESET_WINDOW      = "freeway.http.h2.reset-window";
        // ── Advanced: per-feature detail ──────────────────────────

        /** Minimum response body size in bytes before gzip applies (default 256). */
        public static final String COMPRESSION_MIN_SIZE  = "freeway.http.compression.min-size";
        /** Health endpoint path (default {@code /healthz}). */
        public static final String HEALTH_PATH    = "freeway.http.health.path";
        /** Comma-separated allowed methods (default GET, POST, PUT, DELETE, PATCH, OPTIONS). */
        public static final String CORS_ALLOWED_METHODS   = "freeway.http.cors.allowed-methods";
        /** Comma-separated allowed request headers (default Content-Type, Authorization). */
        public static final String CORS_ALLOWED_HEADERS   = "freeway.http.cors.allowed-headers";
        /** Comma-separated response headers exposed to the browser (default empty). */
        public static final String CORS_EXPOSED_HEADERS   = "freeway.http.cors.exposed-headers";
        /** Preflight cache lifetime in seconds (default 3600). */
        public static final String CORS_MAX_AGE           = "freeway.http.cors.max-age";
        /** Allow credentials on cross-origin requests (default false). */
        public static final String CORS_ALLOW_CREDENTIALS = "freeway.http.cors.allow-credentials";

        // ── Advanced: TLS selection (default = JDK default) ───────

        /** Keystore type: PKCS12 (default) or JKS. */
        public static final String SSL_KEY_STORE_TYPE      = "freeway.http.ssl.key-store-type";
        /** Enable HTTP/2 over TLS via ALPN negotiation (default true). */
        public static final String SSL_HTTP2               = "freeway.http.ssl.http2";
        /** Optional truststore path for validating peer certificates. */
        public static final String SSL_TRUST_STORE         = "freeway.http.ssl.trust-store";
        /** Password for the truststore file. */
        public static final String SSL_TRUST_STORE_PASSWORD = "freeway.http.ssl.trust-store-password";
        /** Truststore type: PKCS12 (default) or JKS. */
        public static final String SSL_TRUST_STORE_TYPE    = "freeway.http.ssl.trust-store-type";
        /** Require client certificates (mTLS). Default false. */
        public static final String SSL_CLIENT_AUTH         = "freeway.http.ssl.client-auth";
        /** Comma-separated TLS protocol versions; empty = JDK default. */
        public static final String SSL_PROTOCOLS           = "freeway.http.ssl.protocols";
        /** Comma-separated TLS cipher suite names; empty = JDK default. */
        public static final String SSL_CIPHERS             = "freeway.http.ssl.ciphers";
        /** Optional directory of per-hostname keystores for SNI certificate
         *  selection; each file is named {@code <host>.p12} (or .jks), and
         *  {@code default.p12} overrides the key-store as the fallback. */
        public static final String SSL_SNI_DIRECTORY       = "freeway.http.ssl.sni-directory";
        /** Certificate reload polling interval (0 disables hot reload). */
        public static final String SSL_RELOAD_INTERVAL     = "freeway.http.ssl.reload-interval";
    }
}
