package com.jujin.freeway.http;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.http.body.BodyTooLargeException;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.AccessLogFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.HealthCheck;
import com.jujin.freeway.http.filter.HealthFilter;
import com.jujin.freeway.http.filter.HttpFilter;
import com.jujin.freeway.http.filter.RequestTimingFilter;
import com.jujin.freeway.http.route.LazyHandler;
import com.jujin.freeway.http.route.Route;
import com.jujin.freeway.http.route.RouteGroup;
import com.jujin.freeway.http.route.RouteIndex;
import com.jujin.freeway.http.ssl.SniKeyManager;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Marker(Builtin.class)
/** Freeway HTTP module: wires routes, filters, engine, WebSocket, SSE, and the server runtime hook. */
public final class HttpModule implements ModuleEx {
    private static final Logger LOG = LoggerFactory.getLogger(HttpModule.class);
    public static final String SERVER_HOOK = "freeway.http.server";
    private volatile SslReloader sslReloader;

    @Override
    public void bind(Binder binder) {
        binder.bind(RouteIndex.class).to(container -> {
            var routes = new ArrayList<>(container.extension(Route.class).all());
            for (var r : routes) {
                if (r.handler() instanceof LazyHandler lh) lh.resolve(container);
            }
            // Resolve LazyHandlers from RouteGroup-expanded routes too
            var allRoutes = new ArrayList<>(routes);
            for (RouteGroup group : container.extension(RouteGroup.class).all()) {
                for (Route expanded : group.expand()) {
                    if (expanded.handler() instanceof LazyHandler lh) lh.resolve(container);
                    allRoutes.add(expanded);
                }
            }
            return new RouteIndex(allRoutes, List.of());
        });
        binder.bind(WebSocketIndex.class).to(WebSocketIndex.class);
        binder.bind(JsonCodec.class).to(JsonCodecDefault.class);

        // CorsFilter — bridge ioC config to plain constructor
        binder.bind(CorsFilter.class).to(container -> {
            var symbols = container.get(SymbolSource.class);
            var coercer = container.get(Coercer.class);
            boolean enabled = config(symbols, coercer,
                HttpConfigKeys.CORS_ENABLED, true);
            String origins = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOWED_ORIGINS, "*");
            String methods = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOWED_METHODS,
                "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            String headers = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOWED_HEADERS,
                "Content-Type, Authorization");
            String exposed = config(symbols, coercer,
                HttpConfigKeys.CORS_EXPOSED_HEADERS, "");
            String maxAge = config(symbols, coercer,
                HttpConfigKeys.CORS_MAX_AGE, "3600");
            boolean credentials = config(symbols, coercer,
                HttpConfigKeys.CORS_ALLOW_CREDENTIALS, false);
            return new CorsFilter(enabled, origins, methods, headers,
                exposed.isBlank() ? null : exposed, maxAge, credentials);
        });

        // Engines — concrete bindings, HTTPS when SSL is enabled
        binder.bind(FreewayHttpEngine.class).to(container -> {
            var json = container.get(JsonCodec.class);
            var coercer = container.get(Coercer.class);
            var symbols = container.get(SymbolSource.class);
            var metrics = container.get(Metrics.class);

            SslSettings ssl = loadSslSettings(symbols, coercer);
            if (!ssl.enabled()) {
                LOG.debug("SSL disabled, using plain HTTP engine");
                return new FreewayHttpEngine(json, coercer, metrics);
            }

            LOG.info("Initializing HTTPS engine from keystore {} (type={}, http2={}, clientAuth={})",
                ssl.keyStorePath(), ssl.keyStoreType(), ssl.http2(), ssl.clientAuth());
            SSLContext sslContext = buildSslContext(ssl);
            SSLParameters sslParameters = buildSslParameters(
                ssl.clientAuth(), ssl.protocols(), ssl.ciphers());
            LOG.info("HTTPS engine initialized — TLS via JDK SSLContext");
            return new FreewayHttpEngine(
                json, coercer, sslContext, ssl.http2(), sslParameters, metrics);
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
                HttpConfigKeys.SERVER_HOST, "127.0.0.1");
            int port = config(symbols, coercer,
                HttpConfigKeys.SERVER_PORT, 8080);
            int backlog = config(symbols, coercer,
                HttpConfigKeys.SERVER_BACKLOG, 0);
            Duration shutdownGrace = config(symbols, coercer,
                HttpConfigKeys.SERVER_SHUTDOWN_GRACE,
                Duration.ofSeconds(2));
            Duration readTimeout = config(symbols, coercer,
                HttpConfigKeys.SERVER_READ_TIMEOUT,
                HttpServerConfig.DEFAULT_READ_TIMEOUT);
            Duration writeTimeout = config(symbols, coercer,
                HttpConfigKeys.SERVER_WRITE_TIMEOUT,
                HttpServerConfig.DEFAULT_WRITE_TIMEOUT);
            int maxConnections = config(symbols, coercer,
                HttpConfigKeys.SERVER_MAX_CONNECTIONS,
                HttpServerConfig.DEFAULT_MAX_CONNECTIONS);
            boolean compressionEnabled = config(symbols, coercer,
                HttpConfigKeys.COMPRESSION_ENABLED, true);
            int compressionMinSize = config(symbols, coercer,
                HttpConfigKeys.COMPRESSION_MIN_SIZE, 256);
            int receiveBufferSize = config(symbols, coercer,
                HttpConfigKeys.SERVER_RECEIVE_BUFFER, 0);
            int sendBufferSize = config(symbols, coercer,
                HttpConfigKeys.SERVER_SEND_BUFFER, 0);
            long maxBodySize = config(symbols, coercer,
                HttpConfigKeys.MAX_BODY_SIZE, HttpServerConfig.DEFAULT_MAX_BODY_SIZE);

            Consumer<Object> eventSink = event ->
                container.get(EventBus.class).publish(event);

            var filters = new ArrayList<>(
                container.extension(HttpFilter.class).all());
            if (config(symbols, coercer,
                    HttpConfigKeys.ACCESS_LOG_ENABLED, false)) {
                filters.add(new AccessLogFilter());
            }

            var pipeline = new RequestPipeline(
                container.get(RouteIndex.class),
                container.get(WebSocketIndex.class),
                container.get(CorsFilter.class),
                container.get(HealthFilter.class),
                container.extension(StaticResourceMount.class).all(),
                List.copyOf(filters),
                container.extension(ExceptionMapper.class).all()
            );

            return new WebServer(
                engine,
                new HttpServerConfig(host, port, backlog,
                    HttpServerConfig.DEFAULT_SOCKET_BUFFER_SIZE, shutdownGrace,
                    maxBodySize, readTimeout, maxConnections, writeTimeout,
                    new HttpServerConfig.CompressionConfig(
                        compressionEnabled, compressionMinSize),
                    receiveBufferSize, sendBufferSize),
                eventSink,
                pipeline
            );
        });

        binder.contribute(RuntimeHook.class).add(SERVER_HOOK, new RuntimeHook() {
            @Override
            public void start(Container container) {
                container.get(WebServer.class).start();
                var symbols = container.get(SymbolSource.class);
                var coercer = container.get(Coercer.class);
                SslSettings ssl = loadSslSettings(symbols, coercer);
                if (ssl.enabled() && ssl.reloadInterval() != null
                        && !ssl.reloadInterval().isZero()) {
                    sslReloader = new SslReloader(
                        container.get(FreewayHttpEngine.class), ssl);
                    sslReloader.start();
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

        binder.bind(HealthCheck.class).to(HealthCheck.Default.class);
        binder.bind(HealthFilter.class).to(container -> {
            var symbols = container.get(SymbolSource.class);
            var coercer = container.get(Coercer.class);
            boolean enabled = config(symbols, coercer,
                HttpConfigKeys.HEALTH_ENABLED, true);
            String path = config(symbols, coercer,
                HttpConfigKeys.HEALTH_PATH, "/healthz");
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

    private record SslSettings(
        boolean enabled,
        String keyStorePath, String keyStorePassword, String keyStoreType,
        boolean http2,
        String trustStorePath, String trustStorePassword, String trustStoreType,
        boolean clientAuth, String protocols, String ciphers,
        String sniDirectory, Duration reloadInterval
    ) {}

    private static SslSettings loadSslSettings(SymbolSource symbols, Coercer coercer) {
        boolean sslEnabled = config(symbols, coercer,
            HttpConfigKeys.SSL_ENABLED, false);
        String ksPath = config(symbols, coercer,
            HttpConfigKeys.SSL_KEY_STORE, null);
        String ksPwd = config(symbols, coercer,
            HttpConfigKeys.SSL_KEY_STORE_PASSWORD, null);
        if (sslEnabled && (ksPath == null || ksPwd == null)) {
            LOG.error("SSL enabled ({} = true) but {} and {} are not configured",
                HttpConfigKeys.SSL_ENABLED,
                HttpConfigKeys.SSL_KEY_STORE,
                HttpConfigKeys.SSL_KEY_STORE_PASSWORD);
            throw new IllegalStateException(
                "SSL is enabled but " + HttpConfigKeys.SSL_KEY_STORE
                + " and " + HttpConfigKeys.SSL_KEY_STORE_PASSWORD
                + " are required");
        }
        return new SslSettings(
            sslEnabled, ksPath, ksPwd,
            config(symbols, coercer, HttpConfigKeys.SSL_KEY_STORE_TYPE, "PKCS12"),
            config(symbols, coercer, HttpConfigKeys.SSL_HTTP2, true),
            config(symbols, coercer, HttpConfigKeys.SSL_TRUST_STORE, null),
            config(symbols, coercer, HttpConfigKeys.SSL_TRUST_STORE_PASSWORD, null),
            config(symbols, coercer, HttpConfigKeys.SSL_TRUST_STORE_TYPE, "PKCS12"),
            config(symbols, coercer, HttpConfigKeys.SSL_CLIENT_AUTH, false),
            config(symbols, coercer, HttpConfigKeys.SSL_PROTOCOLS, null),
            config(symbols, coercer, HttpConfigKeys.SSL_CIPHERS, null),
            config(symbols, coercer, HttpConfigKeys.SSL_SNI_DIRECTORY, null),
            config(symbols, coercer, HttpConfigKeys.SSL_RELOAD_INTERVAL,
                Duration.ZERO));
    }

    private static SSLContext buildSslContext(SslSettings s) {
        try {
            KeyStore defaultStore = loadKeyStore(
                Path.of(s.keyStorePath()), s.keyStoreType(), s.keyStorePassword());
            KeyManager[] keyManagers = s.sniDirectory() != null
                ? buildSniKeyManagers(s, defaultStore)
                : defaultKeyManagers(defaultStore, s.keyStorePassword());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers, buildTrustManagers(s), null);
            return ctx;
        } catch (Exception e) {
            LOG.error("Failed to initialize SSL context from keystore: {} (type={}) — {}",
                s.keyStorePath(), s.keyStoreType(), e.getMessage(), e);
            throw new IllegalStateException(
                "Failed to initialize SSL context from keystore: "
                    + s.keyStorePath(), e);
        }
    }

    private static KeyStore loadKeyStore(Path path, String type, String password)
            throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, password.toCharArray());
        }
        return ks;
    }

    private static KeyManager[] defaultKeyManagers(KeyStore store, String password)
            throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password.toCharArray());
        return kmf.getKeyManagers();
    }

    private static KeyManager[] buildSniKeyManagers(SslSettings s, KeyStore defaultStore)
            throws Exception {
        Path dir = Path.of(s.sniDirectory());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                "SSL SNI directory is not a directory: " + dir);
        }
        Map<String, KeyStore> byHost = new LinkedHashMap<>();
        KeyStore effectiveDefault = defaultStore;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(HttpModule::isKeystoreFile).sorted().toList()) {
                String name = file.getFileName().toString();
                String stem = name.substring(0, name.lastIndexOf('.'));
                if (!isValidSniHost(stem)) {
                    throw new IllegalStateException(
                        "Invalid SNI keystore name (expected <host>.p12/.jks or default.*): "
                            + name);
                }
                String type = storeTypeFor(name, s.keyStoreType());
                KeyStore store = loadKeyStore(file, type, s.keyStorePassword());
                if ("default".equals(stem)) {
                    effectiveDefault = store;
                } else {
                    byHost.put(stem.toLowerCase(Locale.ROOT), store);
                }
            }
        }
        return new KeyManager[]{new SniKeyManager(
            byHost, effectiveDefault, s.keyStorePassword().toCharArray())};
    }

    private static boolean isValidSniHost(String stem) {
        return "default".equals(stem)
            || stem.matches("[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?"
                + "(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*");
    }

    private static boolean isKeystoreFile(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".jks");
    }

    private static String storeTypeFor(String fileName, String fallback) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jks")) return "JKS";
        if (lower.endsWith(".p12") || lower.endsWith(".pfx")) return "PKCS12";
        return fallback;
    }

    private static TrustManager[] buildTrustManagers(SslSettings s) throws Exception {
        if (s.trustStorePath() == null) {
            return null;
        }
        if (s.trustStorePassword() == null) {
            throw new IllegalStateException(
                "SSL trust-store requires " + HttpConfigKeys.SSL_TRUST_STORE_PASSWORD);
        }
        KeyStore trustStore = loadKeyStore(
            Path.of(s.trustStorePath()), s.trustStoreType(), s.trustStorePassword());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        return tmf.getTrustManagers();
    }

    /** Polls keystore mtime/size and swaps a freshly built SSLContext into the
     *  engine when certificate material changes. */
    private final class SslReloader implements AutoCloseable {
        private final FreewayHttpEngine engine;
        private final SslSettings settings;
        private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "freeway-ssl-reload");
                t.setDaemon(true);
                return t;
            });
        private volatile Map<Path, FileStamp> snapshot;

        SslReloader(FreewayHttpEngine engine, SslSettings settings) {
            this.engine = engine;
            this.settings = settings;
        }

        void start() {
            try {
                snapshot = snapshot();
            } catch (IOException e) {
                throw new IllegalStateException(
                    "Cannot snapshot keystore files for reload", e);
            }
            long intervalMillis = Math.max(settings.reloadInterval().toMillis(), 100);
            scheduler.scheduleWithFixedDelay(
                this::check, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }

        private void check() {
            try {
                Map<Path, FileStamp> current = snapshot();
                if (!current.equals(snapshot)) {
                    engine.reload(buildSslContext(settings));
                    snapshot = current;
                    LOG.info("Reloaded HTTPS certificate material ({} keystore file(s))",
                        current.size());
                }
            } catch (Exception e) {
                LOG.error("HTTPS certificate reload failed — keeping previous context", e);
            }
        }

        private Map<Path, FileStamp> snapshot() throws IOException {
            Map<Path, FileStamp> files = new LinkedHashMap<>();
            files.put(Path.of(settings.keyStorePath()),
                stamp(Path.of(settings.keyStorePath())));
            if (settings.sniDirectory() != null) {
                try (var stream = Files.list(Path.of(settings.sniDirectory()))) {
                    for (Path p : stream.filter(HttpModule::isKeystoreFile).sorted().toList()) {
                        files.put(p, stamp(p));
                    }
                }
            }
            return files;
        }

        private static FileStamp stamp(Path path) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(
                path, BasicFileAttributes.class);
            return new FileStamp(attrs.lastModifiedTime().toMillis(), attrs.size());
        }

        @Override
        public void close() {
            scheduler.shutdownNow();
        }
    }

    private record FileStamp(long lastModified, long size) {}

    private static SSLParameters buildSslParameters(
            boolean clientAuth, String protocols, String ciphers) {
        if (!clientAuth && isBlank(protocols) && isBlank(ciphers)) {
            return null;
        }
        SSLParameters params = new SSLParameters();
        if (clientAuth) {
            params.setNeedClientAuth(true);
        }
        if (!isBlank(protocols)) {
            params.setProtocols(splitTokens(protocols));
        }
        if (!isBlank(ciphers)) {
            params.setCipherSuites(splitTokens(ciphers));
        }
        return params;
    }

    private static String[] splitTokens(String value) {
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        return parts;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @SuppressWarnings("unchecked")
    private static <T> T config(SymbolSource symbols, Coercer coercer, String key, T defaultValue) {
        String raw = symbols.resolve(key, null);
        if (raw == null) {
            return defaultValue;
        }
        Class<T> targetType = (Class<T>) (defaultValue != null
            ? defaultValue.getClass() : String.class);
        try {
            return coercer.coerce(raw, targetType);
        } catch (IllegalArgumentException ex) {
            // Same error shape as ConfigSpec.parse: the message names the key
            // and value so a bad http config is fixable without a stack crawl.
            throw new IllegalArgumentException(
                "Invalid value for config key '" + key + "': '" + raw + "'",
                ex
            );
        }
    }
}
