package com.jujin.freeway.http;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The TLS section every HTTPS-capable engine reads: one declaration of the
 * {@code freeway.http.ssl.*} keys, their defaults, and the activation rule.
 */
class SslSettingsTest {

    @Test
    void nothingConfiguredIsPlaintextWithTheDocumentedDefaults() {
        SslSettings ssl = SslSettings.from(source(Map.of()));

        assertFalse(ssl.enabled(), "installing an engine is never a TLS side effect");
        assertNull(ssl.keyStorePath());
        assertNull(ssl.keyStorePassword());
        assertEquals("PKCS12", ssl.keyStoreType());
        assertTrue(ssl.http2(), "h2 over TLS is on by default when TLS is on");
        assertNull(ssl.trustStorePath());
        assertEquals("PKCS12", ssl.trustStoreType());
        assertFalse(ssl.clientAuth());
        assertNull(ssl.protocols());
        assertNull(ssl.ciphers());
        assertNull(ssl.sniDirectory());
        assertEquals(Duration.ZERO, ssl.reloadInterval());
    }

    @Test
    void keystorePresenceActivatesTlsAndTheKillSwitchSuppressesIt() {
        assertTrue(
            SslSettings.from(source(Map.of(HttpConfigKeys.SSL_KEY_STORE, "/tmp/k.p12"))).enabled(),
            "a configured keystore is an HTTPS server");
        assertTrue(
            SslSettings.from(
                    source(
                        Map.of(
                            HttpConfigKeys.SSL_KEY_STORE, "/tmp/k.p12",
                            HttpConfigKeys.SSL_ENABLED, "true")))
                .enabled(),
            "an explicit true enables TLS");
        assertFalse(
            SslSettings.from(
                    source(
                        Map.of(
                            HttpConfigKeys.SSL_KEY_STORE, "/tmp/k.p12",
                            HttpConfigKeys.SSL_ENABLED, "false")))
                .enabled(),
            "an explicit false suppresses a configured keystore");
        assertTrue(
            SslSettings.from(source(Map.of(HttpConfigKeys.SSL_ENABLED, "true"))).enabled(),
            "an explicit true enables TLS even before the keystore is resolved");
        assertFalse(
            SslSettings.from(source(Map.of(HttpConfigKeys.SSL_KEY_STORE, "  "))).enabled(),
            "a blank keystore path is not a configured keystore");
    }

    @Test
    void readsEveryAdapterRelevantKey() {
        Map<String, String> values = new HashMap<>();
        values.put(HttpConfigKeys.SSL_KEY_STORE, "/tmp/k.p12");
        values.put(HttpConfigKeys.SSL_KEY_STORE_PASSWORD, "secret");
        values.put(HttpConfigKeys.SSL_KEY_STORE_TYPE, "JKS");
        values.put(HttpConfigKeys.SSL_HTTP2, "false");
        values.put(HttpConfigKeys.SSL_TRUST_STORE, "/tmp/t.p12");
        values.put(HttpConfigKeys.SSL_TRUST_STORE_PASSWORD, "trust");
        values.put(HttpConfigKeys.SSL_TRUST_STORE_TYPE, "JKS");
        values.put(HttpConfigKeys.SSL_CLIENT_AUTH, "true");
        values.put(HttpConfigKeys.SSL_PROTOCOLS, "TLSv1.3, TLSv1.2");
        values.put(HttpConfigKeys.SSL_CIPHERS, "TLS_AES_128_GCM_SHA256");
        values.put(HttpConfigKeys.SSL_SNI_DIRECTORY, "/tmp/sni");
        values.put(HttpConfigKeys.SSL_RELOAD_INTERVAL, "PT5M");

        SslSettings ssl = SslSettings.from(source(values));

        assertTrue(ssl.enabled());
        assertEquals("/tmp/k.p12", ssl.keyStorePath());
        assertEquals("secret", ssl.keyStorePassword());
        assertEquals("JKS", ssl.keyStoreType());
        assertFalse(ssl.http2());
        assertEquals("/tmp/t.p12", ssl.trustStorePath());
        assertEquals("trust", ssl.trustStorePassword());
        assertEquals("JKS", ssl.trustStoreType());
        assertTrue(ssl.clientAuth());
        assertEquals(List.of("TLSv1.3", "TLSv1.2"), ssl.protocols());
        assertEquals(List.of("TLS_AES_128_GCM_SHA256"), ssl.ciphers());
        assertEquals("/tmp/sni", ssl.sniDirectory());
        assertEquals(Duration.ofMinutes(5), ssl.reloadInterval());
    }

    /**
     * A map-backed source that resolves specs the way the container does: through the configured
     * {@code Coercer}, since the declarations carry no per-key parser.
     */
    private static com.jujin.freeway.ioc.symbol.SymbolSource source(Map<String, String> values) {
        var coercer = new com.jujin.freeway.commons.coercion.CoercerDefault();
        return new com.jujin.freeway.ioc.symbol.SymbolSource() {
            @Override
            public String resolve(String name) {
                String value = values.get(name);
                if (value == null) {
                    throw new com.jujin.freeway.ioc.symbol.UnknownSymbolException(name);
                }
                return value;
            }

            @Override
            public String resolve(String name, String defaultValue) {
                return values.getOrDefault(name, defaultValue);
            }

            @Override
            public String expand(String input) {
                return input;
            }

            @Override
            public <T> T resolve(com.jujin.freeway.ioc.symbol.SymbolSpec<T> spec) {
                return spec.parse(resolve(spec.key(), null), coercer);
            }
        };
    }
}
