package com.jujin.freeway.http.internal;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.time.Duration;

/**
 * Immutable snapshot of all {@code freeway.http.*} configuration, bound once
 * from the IoC {@link SymbolSource} instead of reading each key at every
 * binding site. Internal assembly model — not part of the application API.
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

    public static HttpConfig from(SymbolSource symbols, Coercer coercer) {
        return new HttpConfig(
            config(symbols, coercer, HttpConfigKeys.SERVER_HOST, "127.0.0.1"),
            config(symbols, coercer, HttpConfigKeys.SERVER_PORT, 8080),
            config(symbols, coercer, HttpConfigKeys.SERVER_BACKLOG, 0),
            config(symbols, coercer, HttpConfigKeys.SERVER_SHUTDOWN_GRACE,
                Duration.ofSeconds(2)),
            config(symbols, coercer, HttpConfigKeys.SERVER_READ_TIMEOUT,
                HttpServerConfig.DEFAULT_READ_TIMEOUT),
            config(symbols, coercer, HttpConfigKeys.SERVER_WRITE_TIMEOUT,
                HttpServerConfig.DEFAULT_WRITE_TIMEOUT),
            config(symbols, coercer, HttpConfigKeys.SERVER_MAX_CONNECTIONS,
                HttpServerConfig.DEFAULT_MAX_CONNECTIONS),
            config(symbols, coercer, HttpConfigKeys.COMPRESSION_ENABLED, true),
            config(symbols, coercer, HttpConfigKeys.COMPRESSION_MIN_SIZE, 256),
            config(symbols, coercer, HttpConfigKeys.SERVER_RECEIVE_BUFFER, 0),
            config(symbols, coercer, HttpConfigKeys.SERVER_SEND_BUFFER, 0),
            config(symbols, coercer, HttpConfigKeys.MAX_BODY_SIZE,
                HttpServerConfig.DEFAULT_MAX_BODY_SIZE),
            config(symbols, coercer, HttpConfigKeys.ACCESS_LOG_ENABLED, false),
            new Cors(
                config(symbols, coercer, HttpConfigKeys.CORS_ENABLED, true),
                config(symbols, coercer, HttpConfigKeys.CORS_ALLOWED_ORIGINS, "*"),
                config(symbols, coercer, HttpConfigKeys.CORS_ALLOWED_METHODS,
                    "GET, POST, PUT, DELETE, PATCH, OPTIONS"),
                config(symbols, coercer, HttpConfigKeys.CORS_ALLOWED_HEADERS,
                    "Content-Type, Authorization"),
                config(symbols, coercer, HttpConfigKeys.CORS_EXPOSED_HEADERS, ""),
                config(symbols, coercer, HttpConfigKeys.CORS_MAX_AGE, "3600"),
                config(symbols, coercer, HttpConfigKeys.CORS_ALLOW_CREDENTIALS,
                    false)),
            new Health(
                config(symbols, coercer, HttpConfigKeys.HEALTH_ENABLED, true),
                config(symbols, coercer, HttpConfigKeys.HEALTH_PATH, "/healthz")),
            new Ssl(
                config(symbols, coercer, HttpConfigKeys.SSL_ENABLED, false),
                config(symbols, coercer, HttpConfigKeys.SSL_KEY_STORE, null),
                config(symbols, coercer, HttpConfigKeys.SSL_KEY_STORE_PASSWORD, null),
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
                    Duration.ZERO))
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

    @SuppressWarnings("unchecked")
    private static <T> T config(SymbolSource symbols, Coercer coercer,
                                String key, T defaultValue) {
        String raw = symbols.resolve(key, null);
        if (raw == null) {
            return defaultValue;
        }
        Class<T> targetType = (Class<T>) (defaultValue != null
            ? defaultValue.getClass() : String.class);
        try {
            return coercer.coerce(raw, targetType);
        } catch (IllegalArgumentException ex) {
            // Same error shape as before: the message names the key and value
            // so a bad http config is fixable without a stack crawl.
            throw new IllegalArgumentException(
                "Invalid value for config key '" + key + "': '" + raw + "'",
                ex);
        }
    }
}
