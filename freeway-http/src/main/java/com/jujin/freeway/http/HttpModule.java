package com.jujin.freeway.http;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.metrics.Metrics;
import com.jujin.freeway.http.engine.FreewayHttpEngine;
import com.jujin.freeway.http.engine.SslReloader;
import com.jujin.freeway.http.filter.AccessLogFilter;
import com.jujin.freeway.http.filter.CorsFilter;
import com.jujin.freeway.http.filter.ExceptionMapper;
import com.jujin.freeway.http.filter.ExceptionMappers;
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
        // Config — one immutable snapshot, bound once for the whole module.
        binder.bind(HttpConfig.class).to(container -> HttpConfig.from(
            container.get(SymbolSource.class),
            container.get(Coercer.class)));

        // CorsFilter — bridge ioC config to plain constructor
        binder.bind(CorsFilter.class).to(container -> {
            HttpConfig.Cors cors = container.get(HttpConfig.class).cors();
            return new CorsFilter(cors.enabled(), cors.allowedOrigins(),
                cors.allowedMethods(), cors.allowedHeaders(),
                cors.exposedHeaders().isBlank() ? null : cors.exposedHeaders(),
                cors.maxAge(), cors.allowCredentials());
        });

        // Engines — concrete bindings, HTTPS when SSL is enabled
        binder.bind(FreewayHttpEngine.class).to(container -> {
            var json = container.get(JsonCodec.class);
            var coercer = container.get(Coercer.class);
            var metrics = container.get(Metrics.class);

            HttpConfig.Ssl ssl = container.get(HttpConfig.class).ssl();
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
            HttpConfig cfg = container.get(HttpConfig.class);

            Consumer<Object> eventSink = event ->
                container.get(EventBus.class).publish(event);

            var filters = new ArrayList<>(
                container.extension(HttpFilter.class).all());
            if (cfg.accessLogEnabled()) {
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
                new HttpServerConfig(cfg.host(), cfg.port(), cfg.backlog(),
                    HttpServerConfig.DEFAULT_SOCKET_BUFFER_SIZE, cfg.shutdownGrace(),
                    cfg.maxBodySize(), cfg.readTimeout(), cfg.maxConnections(),
                    cfg.writeTimeout(),
                    new HttpServerConfig.CompressionConfig(
                        cfg.compressionEnabled(), cfg.compressionMinSize()),
                    cfg.receiveBufferSize(), cfg.sendBufferSize()),
                eventSink,
                pipeline
            );
        });

        binder.contribute(RuntimeHook.class).add(SERVER_HOOK, new RuntimeHook() {
            @Override
            public void start(Container container) {
                HttpConfig.Ssl ssl = container.get(HttpConfig.class).ssl();
                container.get(WebServer.class).start();
                if (ssl.enabled() && ssl.reloadInterval() != null
                        && !ssl.reloadInterval().isZero()) {
                    sslReloader = new SslReloader(
                        container.get(FreewayHttpEngine.class),
                        Path.of(ssl.keyStorePath()),
                        ssl.trustStorePath() != null
                            ? Path.of(ssl.trustStorePath()) : null,
                        ssl.sniDirectory() != null
                            ? Path.of(ssl.sniDirectory()) : null,
                        ssl.reloadInterval(),
                        () -> buildSslContext(ssl));
                    try {
                        sslReloader.start();
                    } catch (RuntimeException ex) {
                        sslReloader.close();
                        sslReloader = null;
                        container.get(WebServer.class).stop();
                        throw ex;
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

        binder.bind(HealthCheck.class).to(HealthCheck.Default.class);
        binder.bind(HealthFilter.class).to(container -> {
            HttpConfig.Health health = container.get(HttpConfig.class).health();
            HealthCheck check = container.get(HealthCheck.class);
            return new HealthFilter(health.enabled(), health.path(), check);
        });

        binder.contribute(HttpFilter.class).add(new RequestTimingFilter());

        binder.contribute(ExceptionMapper.class)
            .add(ExceptionMappers.defaultMapper());
    }


    private static SSLContext buildSslContext(HttpConfig.Ssl s) {
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

    private static KeyManager[] buildSniKeyManagers(HttpConfig.Ssl s, KeyStore defaultStore)
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

    private static TrustManager[] buildTrustManagers(HttpConfig.Ssl s) throws Exception {
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

}
