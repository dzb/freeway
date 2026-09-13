package com.jujin.freeway.http;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Builds TLS material from {@link SslSettings}: keystore/truststore loading, SNI
 * key managers, and protocol/cipher restriction. Shared by the built-in HTTPS
 * engine and any adapter that terminates TLS itself (Undertow builds its
 * {@code SSLContext} from here), so keystore handling — including SNI and the
 * null-vs-empty password rule — is identical across engines.
 */
public final class SslContexts {

    private static final Logger LOG = LoggerFactory.getLogger(SslContexts.class);

    private SslContexts() {}

    public static SSLContext build(SslSettings s) {
        if (s.keyStorePath() == null || s.keyStorePath().isBlank()) {
            throw new IllegalStateException(HttpConfigKeys.SSL_KEY_STORE
                + " is required when the HTTPS engine is enabled"
                + " (set the keystore path, or " + HttpConfigKeys.SSL_ENABLED
                + "=false to serve plain HTTP)");
        }
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

    /** Protocol/cipher restriction and client-auth requirement, or null when none applies. */
    public static SSLParameters parameters(SslSettings s) {
        boolean clientAuth = s.clientAuth();
        List<String> protocols = s.protocols();
        List<String> ciphers = s.ciphers();
        if (!clientAuth && (protocols == null || protocols.isEmpty())
                && (ciphers == null || ciphers.isEmpty())) {
            return null;
        }
        SSLParameters params = new SSLParameters();
        if (clientAuth) {
            params.setNeedClientAuth(true);
        }
        if (protocols != null && !protocols.isEmpty()) {
            params.setProtocols(protocols.toArray(String[]::new));
        }
        if (ciphers != null && !ciphers.isEmpty()) {
            params.setCipherSuites(ciphers.toArray(String[]::new));
        }
        return params;
    }

    private static KeyStore loadKeyStore(Path path, String type, String password)
            throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, keyStorePasswordChars(password));
        }
        return ks;
    }

    private static KeyManager[] defaultKeyManagers(KeyStore store, String password)
            throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, keyStorePasswordChars(password));
        return kmf.getKeyManagers();
    }

    /**
     * A keystore may legitimately have no password (e.g. a PKCS#12 store created
     * with an empty password), in which case {@code KeyStore.load} expects a
     * {@code null} char array — not an empty one. Passing {@code null} straight
     * through avoids the NPE that {@code password.toCharArray()} would throw when
     * the configured password is absent.
     */
    private static char[] keyStorePasswordChars(String password) {
        return password == null ? null : password.toCharArray();
    }

    private static KeyManager[] buildSniKeyManagers(SslSettings s,
                                                    KeyStore defaultStore)
            throws Exception {
        Path dir = Path.of(s.sniDirectory());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                "SSL SNI directory is not a directory: " + dir);
        }
        Map<String, KeyStore> byHost = new LinkedHashMap<>();
        KeyStore effectiveDefault = defaultStore;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(SslContexts::isKeystoreFile)
                    .sorted().toList()) {
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
            byHost, effectiveDefault, keyStorePasswordChars(s.keyStorePassword()))};
    }

    private static TrustManager[] buildTrustManagers(SslSettings s)
            throws Exception {
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
}
