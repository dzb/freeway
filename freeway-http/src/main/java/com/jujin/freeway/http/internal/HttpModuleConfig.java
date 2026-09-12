package com.jujin.freeway.http.internal;


import com.jujin.freeway.ioc.symbol.SymbolSpec;
import com.jujin.freeway.http.HttpConfigKeys;
import com.jujin.freeway.http.HttpServerConfig;
import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/**
 * The {@code HttpModule}'s resolved configuration: one immutable snapshot,
 * bound once from the IoC {@link SymbolSource} instead of reading each key
 * at every binding site. Keys, types and defaults are declared once as
 * {@link SymbolSpec}s below; the symbol chain resolves each key and the
 * specs post-process it.
 *
 * <p>Two faces, one resolution: the {@link HttpServerConfig} is the
 * <b>engine contract</b> — the public record the built-in engine (and ext
 * adapters via {@code HttpEngine}) receive — while {@link #cors()},
 * {@link #health()}, {@link #ssl()} and {@link #accessLogEnabled()} are the
 * assembly face consumed by the module when wiring routes, filters and the
 * TLS engine. Engines never see CORS or SSL; the module does not keep a
 * second copy of any server field.
 *
 * <p>Internal assembly model — not part of the application API.
 */
public record HttpModuleConfig(
    HttpServerConfig server,
    Cors cors,
    Health health,
    Ssl ssl,
    boolean accessLogEnabled
) {

    public record Cors(boolean enabled, List<String> allowedOrigins,
                       List<String> allowedMethods, List<String> allowedHeaders,
                       List<String> exposedHeaders, String maxAge,
                       boolean allowCredentials) {}

    public record Health(boolean enabled, String path) {}

    public record Ssl(boolean enabled, String keyStorePath,
                      String keyStorePassword, String keyStoreType,
                      boolean http2, String trustStorePath,
                      String trustStorePassword, String trustStoreType,
                      boolean clientAuth, List<String> protocols,
                      List<String> ciphers, String sniDirectory,
                      Duration reloadInterval) {

        /**
         * Presence-driven activation via {@link SymbolSpec}: an explicit
         * {@code ssl.enabled} wins — {@code true} or {@code false} (the kill
         * switch suppressing a configured keystore); unset falls to keystore
         * presence — a configured keystore is an HTTPS server. Nothing set
         * is plaintext: installing HttpModule is never a TLS side effect.
         */
        static boolean activated(String enabledRaw, String keyStorePath) {
            return SymbolSpec.activated(HttpConfigKeys.SSL_ENABLED, enabledRaw,
                keyStorePath != null && !keyStorePath.isBlank());
        }
    }

    // ── Key declarations: name, type and default stated exactly once ──

    private static final SymbolSpec<String> SERVER_HOST =
        SymbolSpec.of(HttpConfigKeys.SERVER_HOST, String.class, "127.0.0.1");
    private static final SymbolSpec<Integer> SERVER_PORT =
        SymbolSpec.of(HttpConfigKeys.SERVER_PORT, Integer.class, 8080);
    private static final SymbolSpec<Integer> SERVER_BACKLOG =
        SymbolSpec.of(HttpConfigKeys.SERVER_BACKLOG, Integer.class, 0);
    private static final SymbolSpec<Duration> SERVER_SHUTDOWN_GRACE =
        SymbolSpec.of(HttpConfigKeys.SERVER_SHUTDOWN_GRACE, Duration.class,
            Duration.ofSeconds(2));
    private static final SymbolSpec<Duration> SERVER_READ_TIMEOUT =
        SymbolSpec.of(HttpConfigKeys.SERVER_READ_TIMEOUT, Duration.class,
            HttpServerConfig.DEFAULT_READ_TIMEOUT);
    private static final SymbolSpec<Duration> SERVER_WRITE_TIMEOUT =
        SymbolSpec.of(HttpConfigKeys.SERVER_WRITE_TIMEOUT, Duration.class,
            HttpServerConfig.DEFAULT_WRITE_TIMEOUT);
    private static final SymbolSpec<Integer> SERVER_MAX_CONNECTIONS =
        SymbolSpec.of(HttpConfigKeys.SERVER_MAX_CONNECTIONS, Integer.class,
            HttpServerConfig.DEFAULT_MAX_CONNECTIONS);
    private static final SymbolSpec<Boolean> COMPRESSION_ENABLED =
        SymbolSpec.of(HttpConfigKeys.COMPRESSION_ENABLED, Boolean.class, true);
    private static final SymbolSpec<Integer> COMPRESSION_MIN_SIZE =
        SymbolSpec.of(HttpConfigKeys.COMPRESSION_MIN_SIZE, Integer.class, 256);
    private static final SymbolSpec<Integer> SERVER_RECEIVE_BUFFER =
        SymbolSpec.of(HttpConfigKeys.SERVER_RECEIVE_BUFFER, Integer.class, 0);
    private static final SymbolSpec<Integer> SERVER_SEND_BUFFER =
        SymbolSpec.of(HttpConfigKeys.SERVER_SEND_BUFFER, Integer.class, 0);
    private static final SymbolSpec<Long> MAX_BODY_SIZE =
        SymbolSpec.of(HttpConfigKeys.MAX_BODY_SIZE, Long.class,
            HttpServerConfig.DEFAULT_MAX_BODY_SIZE);
    private static final SymbolSpec<Boolean> ACCESS_LOG_ENABLED =
        SymbolSpec.of(HttpConfigKeys.ACCESS_LOG_ENABLED, Boolean.class, false);

    private static final SymbolSpec<Boolean> CORS_ENABLED =
        SymbolSpec.of(HttpConfigKeys.CORS_ENABLED, Boolean.class, true);
    private static final SymbolSpec<List<String>> CORS_ALLOWED_ORIGINS =
        SymbolSpec.list(HttpConfigKeys.CORS_ALLOWED_ORIGINS, List.of("*"));
    private static final SymbolSpec<List<String>> CORS_ALLOWED_METHODS =
        SymbolSpec.list(HttpConfigKeys.CORS_ALLOWED_METHODS,
            List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    private static final SymbolSpec<List<String>> CORS_ALLOWED_HEADERS =
        SymbolSpec.list(HttpConfigKeys.CORS_ALLOWED_HEADERS,
            List.of("Content-Type", "Authorization"));
    private static final SymbolSpec<List<String>> CORS_EXPOSED_HEADERS =
        SymbolSpec.list(HttpConfigKeys.CORS_EXPOSED_HEADERS, List.of());
    private static final SymbolSpec<Integer> CORS_MAX_AGE =
        SymbolSpec.of(HttpConfigKeys.CORS_MAX_AGE, Integer.class, 3600);
    private static final SymbolSpec<Boolean> CORS_ALLOW_CREDENTIALS =
        SymbolSpec.of(HttpConfigKeys.CORS_ALLOW_CREDENTIALS, Boolean.class, false);

    private static final SymbolSpec<Boolean> HEALTH_ENABLED =
        SymbolSpec.of(HttpConfigKeys.HEALTH_ENABLED, Boolean.class, true);
    private static final SymbolSpec<String> HEALTH_PATH =
        SymbolSpec.of(HttpConfigKeys.HEALTH_PATH, String.class, "/healthz");

    private static final SymbolSpec<String> SSL_ENABLED =
        SymbolSpec.of(HttpConfigKeys.SSL_ENABLED, String.class, null, Function.identity());
    private static final SymbolSpec<String> SSL_KEY_STORE =
        SymbolSpec.of(HttpConfigKeys.SSL_KEY_STORE, String.class, null);
    private static final SymbolSpec<String> SSL_KEY_STORE_PASSWORD =
        SymbolSpec.of(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, String.class, null);
    private static final SymbolSpec<String> SSL_KEY_STORE_TYPE =
        SymbolSpec.of(HttpConfigKeys.SSL_KEY_STORE_TYPE, String.class, "PKCS12");
    private static final SymbolSpec<Boolean> SSL_HTTP2 =
        SymbolSpec.of(HttpConfigKeys.SSL_HTTP2, Boolean.class, true);
    private static final SymbolSpec<String> SSL_TRUST_STORE =
        SymbolSpec.of(HttpConfigKeys.SSL_TRUST_STORE, String.class, null);
    private static final SymbolSpec<String> SSL_TRUST_STORE_PASSWORD =
        SymbolSpec.of(HttpConfigKeys.SSL_TRUST_STORE_PASSWORD, String.class, null);
    private static final SymbolSpec<String> SSL_TRUST_STORE_TYPE =
        SymbolSpec.of(HttpConfigKeys.SSL_TRUST_STORE_TYPE, String.class, "PKCS12");
    private static final SymbolSpec<Boolean> SSL_CLIENT_AUTH =
        SymbolSpec.of(HttpConfigKeys.SSL_CLIENT_AUTH, Boolean.class, false);
    private static final SymbolSpec<List<String>> SSL_PROTOCOLS =
        SymbolSpec.list(HttpConfigKeys.SSL_PROTOCOLS, null);
    private static final SymbolSpec<List<String>> SSL_CIPHERS =
        SymbolSpec.list(HttpConfigKeys.SSL_CIPHERS, null);
    private static final SymbolSpec<String> SSL_SNI_DIRECTORY =
        SymbolSpec.of(HttpConfigKeys.SSL_SNI_DIRECTORY, String.class, null);
    private static final SymbolSpec<Duration> SSL_RELOAD_INTERVAL =
        SymbolSpec.of(HttpConfigKeys.SSL_RELOAD_INTERVAL, Duration.class,
            Duration.ZERO);
    private static final SymbolSpec<Integer> H2_RESET_BURST_LIMIT =
        SymbolSpec.of(HttpConfigKeys.H2_RESET_BURST_LIMIT, Integer.class,
            HttpServerConfig.DEFAULT_H2_RESET_BURST_LIMIT);
    private static final SymbolSpec<Duration> H2_RESET_WINDOW =
        SymbolSpec.of(HttpConfigKeys.H2_RESET_WINDOW, Duration.class,
            HttpServerConfig.DEFAULT_H2_RESET_WINDOW);

    /**
     * Resolves every {@code freeway.http.*} key once through the symbol
     * chain (one step — the chain's Coercer covers coercer-backed specs)
     * and assembles the two faces: the {@link HttpServerConfig} engine
     * contract plus the assembly-face records.
     */
    public static HttpModuleConfig from(SymbolSource symbols) {
        String sslEnabledRaw = symbols.resolve(SSL_ENABLED);
        String sslKeyStore = symbols.resolve(SSL_KEY_STORE);
        boolean sslEnabled = Ssl.activated(sslEnabledRaw, sslKeyStore);
        return new HttpModuleConfig(
            new HttpServerConfig(
                symbols.resolve(SERVER_HOST),
                symbols.resolve(SERVER_PORT),
                symbols.resolve(SERVER_BACKLOG),
                symbols.resolve(SERVER_SHUTDOWN_GRACE),
                symbols.resolve(MAX_BODY_SIZE),
                symbols.resolve(SERVER_READ_TIMEOUT),
                symbols.resolve(SERVER_MAX_CONNECTIONS),
                symbols.resolve(SERVER_WRITE_TIMEOUT),
                new HttpServerConfig.CompressionConfig(
                    symbols.resolve(COMPRESSION_ENABLED),
                    symbols.resolve(COMPRESSION_MIN_SIZE)),
                symbols.resolve(SERVER_RECEIVE_BUFFER),
                symbols.resolve(SERVER_SEND_BUFFER),
                symbols.resolve(H2_RESET_BURST_LIMIT),
                symbols.resolve(H2_RESET_WINDOW)),
            new Cors(
                symbols.resolve(CORS_ENABLED),
                symbols.resolve(CORS_ALLOWED_ORIGINS),
                symbols.resolve(CORS_ALLOWED_METHODS),
                symbols.resolve(CORS_ALLOWED_HEADERS),
                symbols.resolve(CORS_EXPOSED_HEADERS),
                String.valueOf(symbols.resolve(CORS_MAX_AGE)),
                symbols.resolve(CORS_ALLOW_CREDENTIALS)),
            new Health(
                symbols.resolve(HEALTH_ENABLED),
                symbols.resolve(HEALTH_PATH)),
            new Ssl(
                sslEnabled,
                sslKeyStore,
                symbols.resolve(SSL_KEY_STORE_PASSWORD),
                symbols.resolve(SSL_KEY_STORE_TYPE),
                symbols.resolve(SSL_HTTP2),
                symbols.resolve(SSL_TRUST_STORE),
                symbols.resolve(SSL_TRUST_STORE_PASSWORD),
                symbols.resolve(SSL_TRUST_STORE_TYPE),
                symbols.resolve(SSL_CLIENT_AUTH),
                symbols.resolve(SSL_PROTOCOLS),
                symbols.resolve(SSL_CIPHERS),
                symbols.resolve(SSL_SNI_DIRECTORY),
                symbols.resolve(SSL_RELOAD_INTERVAL)),
            symbols.resolve(ACCESS_LOG_ENABLED)
        );
    }
}
