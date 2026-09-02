package com.jujin.freeway.http.internal;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.config.ConfigSpec;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.time.Duration;

/**
 * Immutable snapshot of all {@code freeway.http.*} configuration, bound once
 * from the IoC {@link SymbolSource} instead of reading each key at every
 * binding site. Keys, types and defaults are declared once as
 * {@link ConfigSpec}s below; the chain resolves each key to its raw value
 * and the specs post-process it into the typed form. Internal assembly
 * model — not part of the application API.
 */
public record HttpConfig(
    String host, int port, int backlog, Duration shutdownGrace,
    Duration readTimeout, Duration writeTimeout, int maxConnections,
    boolean compressionEnabled, int compressionMinSize,
    int receiveBufferSize, int sendBufferSize, long maxBodySize,
    boolean accessLogEnabled,
    Cors cors,
    Health health,
    Ssl ssl
) {

    public record Cors(boolean enabled, String allowedOrigins,
                       String allowedMethods, String allowedHeaders,
                       String exposedHeaders, String maxAge,
                       boolean allowCredentials) {}

    public record Health(boolean enabled, String path) {}

    public record Ssl(boolean enabled, String keyStorePath,
                      String keyStorePassword, String keyStoreType,
                      boolean http2, String trustStorePath,
                      String trustStorePassword, String trustStoreType,
                      boolean clientAuth, String protocols, String ciphers,
                      String sniDirectory, Duration reloadInterval) {}

    // ── Key declarations: name, type and default stated exactly once ──

    private static final ConfigSpec<String> SERVER_HOST =
        ConfigSpec.of(HttpConfigKeys.SERVER_HOST, String.class, "127.0.0.1");
    private static final ConfigSpec<Integer> SERVER_PORT =
        ConfigSpec.of(HttpConfigKeys.SERVER_PORT, Integer.class, 8080);
    private static final ConfigSpec<Integer> SERVER_BACKLOG =
        ConfigSpec.of(HttpConfigKeys.SERVER_BACKLOG, Integer.class, 0);
    private static final ConfigSpec<Duration> SERVER_SHUTDOWN_GRACE =
        ConfigSpec.of(HttpConfigKeys.SERVER_SHUTDOWN_GRACE, Duration.class,
            Duration.ofSeconds(2));
    private static final ConfigSpec<Duration> SERVER_READ_TIMEOUT =
        ConfigSpec.of(HttpConfigKeys.SERVER_READ_TIMEOUT, Duration.class,
            HttpServerConfig.DEFAULT_READ_TIMEOUT);
    private static final ConfigSpec<Duration> SERVER_WRITE_TIMEOUT =
        ConfigSpec.of(HttpConfigKeys.SERVER_WRITE_TIMEOUT, Duration.class,
            HttpServerConfig.DEFAULT_WRITE_TIMEOUT);
    private static final ConfigSpec<Integer> SERVER_MAX_CONNECTIONS =
        ConfigSpec.of(HttpConfigKeys.SERVER_MAX_CONNECTIONS, Integer.class,
            HttpServerConfig.DEFAULT_MAX_CONNECTIONS);
    private static final ConfigSpec<Boolean> COMPRESSION_ENABLED =
        ConfigSpec.of(HttpConfigKeys.COMPRESSION_ENABLED, Boolean.class, true);
    private static final ConfigSpec<Integer> COMPRESSION_MIN_SIZE =
        ConfigSpec.of(HttpConfigKeys.COMPRESSION_MIN_SIZE, Integer.class, 256);
    private static final ConfigSpec<Integer> SERVER_RECEIVE_BUFFER =
        ConfigSpec.of(HttpConfigKeys.SERVER_RECEIVE_BUFFER, Integer.class, 0);
    private static final ConfigSpec<Integer> SERVER_SEND_BUFFER =
        ConfigSpec.of(HttpConfigKeys.SERVER_SEND_BUFFER, Integer.class, 0);
    private static final ConfigSpec<Long> MAX_BODY_SIZE =
        ConfigSpec.of(HttpConfigKeys.MAX_BODY_SIZE, Long.class,
            HttpServerConfig.DEFAULT_MAX_BODY_SIZE);
    private static final ConfigSpec<Boolean> ACCESS_LOG_ENABLED =
        ConfigSpec.of(HttpConfigKeys.ACCESS_LOG_ENABLED, Boolean.class, false);

    private static final ConfigSpec<Boolean> CORS_ENABLED =
        ConfigSpec.of(HttpConfigKeys.CORS_ENABLED, Boolean.class, true);
    private static final ConfigSpec<String> CORS_ALLOWED_ORIGINS =
        ConfigSpec.of(HttpConfigKeys.CORS_ALLOWED_ORIGINS, String.class, "*");
    private static final ConfigSpec<String> CORS_ALLOWED_METHODS =
        ConfigSpec.of(HttpConfigKeys.CORS_ALLOWED_METHODS, String.class,
            "GET, POST, PUT, DELETE, PATCH, OPTIONS");
    private static final ConfigSpec<String> CORS_ALLOWED_HEADERS =
        ConfigSpec.of(HttpConfigKeys.CORS_ALLOWED_HEADERS, String.class,
            "Content-Type, Authorization");
    private static final ConfigSpec<String> CORS_EXPOSED_HEADERS =
        ConfigSpec.of(HttpConfigKeys.CORS_EXPOSED_HEADERS, String.class, "");
    private static final ConfigSpec<String> CORS_MAX_AGE =
        ConfigSpec.of(HttpConfigKeys.CORS_MAX_AGE, String.class, "3600");
    private static final ConfigSpec<Boolean> CORS_ALLOW_CREDENTIALS =
        ConfigSpec.of(HttpConfigKeys.CORS_ALLOW_CREDENTIALS, Boolean.class, false);

    private static final ConfigSpec<Boolean> HEALTH_ENABLED =
        ConfigSpec.of(HttpConfigKeys.HEALTH_ENABLED, Boolean.class, true);
    private static final ConfigSpec<String> HEALTH_PATH =
        ConfigSpec.of(HttpConfigKeys.HEALTH_PATH, String.class, "/healthz");

    private static final ConfigSpec<Boolean> SSL_ENABLED =
        ConfigSpec.of(HttpConfigKeys.SSL_ENABLED, Boolean.class, false);
    private static final ConfigSpec<String> SSL_KEY_STORE =
        ConfigSpec.of(HttpConfigKeys.SSL_KEY_STORE, String.class, null);
    private static final ConfigSpec<String> SSL_KEY_STORE_PASSWORD =
        ConfigSpec.of(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, String.class, null);
    private static final ConfigSpec<String> SSL_KEY_STORE_TYPE =
        ConfigSpec.of(HttpConfigKeys.SSL_KEY_STORE_TYPE, String.class, "PKCS12");
    private static final ConfigSpec<Boolean> SSL_HTTP2 =
        ConfigSpec.of(HttpConfigKeys.SSL_HTTP2, Boolean.class, true);
    private static final ConfigSpec<String> SSL_TRUST_STORE =
        ConfigSpec.of(HttpConfigKeys.SSL_TRUST_STORE, String.class, null);
    private static final ConfigSpec<String> SSL_TRUST_STORE_PASSWORD =
        ConfigSpec.of(HttpConfigKeys.SSL_TRUST_STORE_PASSWORD, String.class, null);
    private static final ConfigSpec<String> SSL_TRUST_STORE_TYPE =
        ConfigSpec.of(HttpConfigKeys.SSL_TRUST_STORE_TYPE, String.class, "PKCS12");
    private static final ConfigSpec<Boolean> SSL_CLIENT_AUTH =
        ConfigSpec.of(HttpConfigKeys.SSL_CLIENT_AUTH, Boolean.class, false);
    private static final ConfigSpec<String> SSL_PROTOCOLS =
        ConfigSpec.of(HttpConfigKeys.SSL_PROTOCOLS, String.class, null);
    private static final ConfigSpec<String> SSL_CIPHERS =
        ConfigSpec.of(HttpConfigKeys.SSL_CIPHERS, String.class, null);
    private static final ConfigSpec<String> SSL_SNI_DIRECTORY =
        ConfigSpec.of(HttpConfigKeys.SSL_SNI_DIRECTORY, String.class, null);
    private static final ConfigSpec<Duration> SSL_RELOAD_INTERVAL =
        ConfigSpec.of(HttpConfigKeys.SSL_RELOAD_INTERVAL, Duration.class,
            Duration.ZERO);

    /**
     * The post-processing step for one resolved value: the symbol chain
     * answers with a raw string, the spec turns it into the typed form
     * (default for absent, key-named error for malformed).
     */
    private static <T> T config(SymbolSource symbols, Coercer coercer, ConfigSpec<T> spec) {
        return spec.parse(symbols.resolve(spec.key(), null), coercer);
    }

    public static HttpConfig from(SymbolSource symbols, Coercer coercer) {
        return new HttpConfig(
            config(symbols, coercer, SERVER_HOST),
            config(symbols, coercer, SERVER_PORT),
            config(symbols, coercer, SERVER_BACKLOG),
            config(symbols, coercer, SERVER_SHUTDOWN_GRACE),
            config(symbols, coercer, SERVER_READ_TIMEOUT),
            config(symbols, coercer, SERVER_WRITE_TIMEOUT),
            config(symbols, coercer, SERVER_MAX_CONNECTIONS),
            config(symbols, coercer, COMPRESSION_ENABLED),
            config(symbols, coercer, COMPRESSION_MIN_SIZE),
            config(symbols, coercer, SERVER_RECEIVE_BUFFER),
            config(symbols, coercer, SERVER_SEND_BUFFER),
            config(symbols, coercer, MAX_BODY_SIZE),
            config(symbols, coercer, ACCESS_LOG_ENABLED),
            new Cors(
                config(symbols, coercer, CORS_ENABLED),
                config(symbols, coercer, CORS_ALLOWED_ORIGINS),
                config(symbols, coercer, CORS_ALLOWED_METHODS),
                config(symbols, coercer, CORS_ALLOWED_HEADERS),
                config(symbols, coercer, CORS_EXPOSED_HEADERS),
                config(symbols, coercer, CORS_MAX_AGE),
                config(symbols, coercer, CORS_ALLOW_CREDENTIALS)),
            new Health(
                config(symbols, coercer, HEALTH_ENABLED),
                config(symbols, coercer, HEALTH_PATH)),
            new Ssl(
                config(symbols, coercer, SSL_ENABLED),
                config(symbols, coercer, SSL_KEY_STORE),
                config(symbols, coercer, SSL_KEY_STORE_PASSWORD),
                config(symbols, coercer, SSL_KEY_STORE_TYPE),
                config(symbols, coercer, SSL_HTTP2),
                config(symbols, coercer, SSL_TRUST_STORE),
                config(symbols, coercer, SSL_TRUST_STORE_PASSWORD),
                config(symbols, coercer, SSL_TRUST_STORE_TYPE),
                config(symbols, coercer, SSL_CLIENT_AUTH),
                config(symbols, coercer, SSL_PROTOCOLS),
                config(symbols, coercer, SSL_CIPHERS),
                config(symbols, coercer, SSL_SNI_DIRECTORY),
                config(symbols, coercer, SSL_RELOAD_INTERVAL))
        );
    }

    /** Maps this IoC config snapshot to the engine's server config. */
    public HttpServerConfig toServerConfig() {
        return new HttpServerConfig(
            host, port, backlog,
            shutdownGrace, maxBodySize, readTimeout, maxConnections, writeTimeout,
            new HttpServerConfig.CompressionConfig(
                compressionEnabled, compressionMinSize),
            receiveBufferSize, sendBufferSize);
    }
}
