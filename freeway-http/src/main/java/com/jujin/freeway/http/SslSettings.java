package com.jujin.freeway.http;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import com.jujin.freeway.ioc.symbol.SymbolSource;
import com.jujin.freeway.ioc.symbol.SymbolSpec;

/**
 * The {@code freeway.http.ssl.*} settings, resolved once: every HTTPS-capable
 * engine — the built-in one and the transport adapters — reads the same keys,
 * applies the same defaults, and makes the same activation decision from this
 * value instead of resolving the keys itself.
 *
 * <p>Activation is presence-driven via {@link SymbolSpec#activated}: an explicit
 * {@code ssl.enabled} wins — {@code true} or {@code false} (the kill switch
 * suppressing a configured keystore); unset falls back to keystore presence — a
 * configured keystore is an HTTPS server. Nothing set is plaintext: enabling an
 * engine is never a TLS side effect.
 *
 * <p>Key names, types and defaults are declared here exactly once; an adapter
 * adds only its own keys (for example Jetty's {@code ssl.key-password} and
 * {@code ssl.key-alias}).
 */
public record SslSettings(
    boolean enabled,
    String keyStorePath,
    String keyStorePassword,
    String keyStoreType,
    boolean http2,
    String trustStorePath,
    String trustStorePassword,
    String trustStoreType,
    boolean clientAuth,
    List<String> protocols,
    List<String> ciphers,
    String sniDirectory,
    Duration reloadInterval
) {

    private static final SymbolSpec<String> ENABLED =
        SymbolSpec.of(HttpConfigKeys.SSL_ENABLED, String.class, null, Function.identity());
    private static final SymbolSpec<String> KEY_STORE =
        SymbolSpec.of(HttpConfigKeys.SSL_KEY_STORE, String.class, null);
    private static final SymbolSpec<String> KEY_STORE_PASSWORD =
        SymbolSpec.of(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, String.class, null);
    private static final SymbolSpec<String> KEY_STORE_TYPE =
        SymbolSpec.of(HttpConfigKeys.SSL_KEY_STORE_TYPE, String.class, "PKCS12");
    private static final SymbolSpec<Boolean> HTTP2 =
        SymbolSpec.of(HttpConfigKeys.SSL_HTTP2, Boolean.class, true);
    private static final SymbolSpec<String> TRUST_STORE =
        SymbolSpec.of(HttpConfigKeys.SSL_TRUST_STORE, String.class, null);
    private static final SymbolSpec<String> TRUST_STORE_PASSWORD =
        SymbolSpec.of(HttpConfigKeys.SSL_TRUST_STORE_PASSWORD, String.class, null);
    private static final SymbolSpec<String> TRUST_STORE_TYPE =
        SymbolSpec.of(HttpConfigKeys.SSL_TRUST_STORE_TYPE, String.class, "PKCS12");
    private static final SymbolSpec<Boolean> CLIENT_AUTH =
        SymbolSpec.of(HttpConfigKeys.SSL_CLIENT_AUTH, Boolean.class, false);
    private static final SymbolSpec<List<String>> PROTOCOLS =
        SymbolSpec.list(HttpConfigKeys.SSL_PROTOCOLS, null);
    private static final SymbolSpec<List<String>> CIPHERS =
        SymbolSpec.list(HttpConfigKeys.SSL_CIPHERS, null);
    private static final SymbolSpec<String> SNI_DIRECTORY =
        SymbolSpec.of(HttpConfigKeys.SSL_SNI_DIRECTORY, String.class, null);
    private static final SymbolSpec<Duration> RELOAD_INTERVAL =
        SymbolSpec.of(HttpConfigKeys.SSL_RELOAD_INTERVAL, Duration.class, Duration.ZERO);

    /** Resolves the whole TLS section from the configuration chain. */
    public static SslSettings from(SymbolSource symbols) {
        return new SslSettings(
            SymbolSpec.activated(
                HttpConfigKeys.SSL_ENABLED,
                symbols.resolve(ENABLED),
                present(symbols.resolve(KEY_STORE))),
            symbols.resolve(KEY_STORE),
            symbols.resolve(KEY_STORE_PASSWORD),
            symbols.resolve(KEY_STORE_TYPE),
            symbols.resolve(HTTP2),
            symbols.resolve(TRUST_STORE),
            symbols.resolve(TRUST_STORE_PASSWORD),
            symbols.resolve(TRUST_STORE_TYPE),
            symbols.resolve(CLIENT_AUTH),
            symbols.resolve(PROTOCOLS),
            symbols.resolve(CIPHERS),
            symbols.resolve(SNI_DIRECTORY),
            symbols.resolve(RELOAD_INTERVAL));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
