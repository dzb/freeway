package com.jujin.freeway.http;

import java.net.Socket;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

/**
 * Server-side {@link X509KeyManager} that selects a certificate chain from the
 * SNI hostname in the ClientHello, falling back to a default entry. Each
 * keystore is expected to hold exactly one private-key entry; entries are
 * addressed through unique synthetic aliases so the JDK's aggregated key
 * manager can resolve {@code getCertificateChain}/{@code getPrivateKey}.
 */
final class SniKeyManager extends X509ExtendedKeyManager {

    private record Entry(String uniqueAlias, X509KeyManager delegate, String storeAlias) {}

    private final Map<String, Entry> bySni = new HashMap<>();
    private final Map<String, Entry> byAlias = new HashMap<>();
    private final Entry defaultEntry;

    /**
     * @param storesByHost SNI hostname (lower-case) to a keystore containing
     *                     the certificate for that host
     * @param defaultStore keystore used when SNI is absent or unmatched
     * @param password     password for every keystore
     */
    public SniKeyManager(Map<String, KeyStore> storesByHost,
                         KeyStore defaultStore, char[] password) throws Exception {
        Objects.requireNonNull(defaultStore, "defaultStore");
        this.defaultEntry = entry("default", defaultStore, password);
        byAlias.put(defaultEntry.uniqueAlias(), defaultEntry);
        for (var e : storesByHost.entrySet()) {
            Entry entry = entry(e.getKey(), e.getValue(), password);
            bySni.put(e.getKey().toLowerCase(Locale.ROOT), entry);
            byAlias.put(entry.uniqueAlias(), entry);
        }
    }

    private static Entry entry(String host, KeyStore store, char[] password)
            throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, password);
        X509KeyManager delegate = null;
        for (var km : kmf.getKeyManagers()) {
            if (km instanceof X509KeyManager x509) {
                delegate = x509;
                break;
            }
        }
        if (delegate == null) {
            throw new IllegalStateException(
                "No X509KeyManager produced for SNI entry '" + host + "'");
        }
        String storeAlias = null;
        var aliases = store.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (store.isKeyEntry(alias)) {
                storeAlias = alias;
                break;
            }
        }
        if (storeAlias == null) {
            throw new IllegalStateException(
                "SNI keystore '" + host + "' contains no private key entry");
        }
        return new Entry("sni#" + host, delegate, storeAlias);
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers,
                                    Socket socket) {
        Entry entry = resolve(socket instanceof SSLSocket ssl
            ? ssl.getHandshakeSession() : null);
        return entry == null ? null : entry.uniqueAlias();
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers,
                                          SSLEngine engine) {
        Entry entry = resolve(engine == null ? null : engine.getHandshakeSession());
        return entry == null ? null : entry.uniqueAlias();
    }

    private Entry resolve(SSLSession session) {
        if (session instanceof ExtendedSSLSession extended) {
            for (SNIServerName name : extended.getRequestedServerNames()) {
                if (name.getType() == StandardConstants.SNI_HOST_NAME) {
                    Entry entry = bySni.get(
                        ((SNIHostName) name).getAsciiName().toLowerCase(Locale.ROOT));
                    if (entry != null) return entry;
                    break;
                }
            }
        }
        return defaultEntry;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        Entry entry = byAlias.get(alias);
        return entry == null ? null
            : entry.delegate().getCertificateChain(entry.storeAlias());
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        Entry entry = byAlias.get(alias);
        return entry == null ? null
            : entry.delegate().getPrivateKey(entry.storeAlias());
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        List<String> aliases = new ArrayList<>(byAlias.size());
        for (var entry : byAlias.values()) {
            if (entry.delegate().getCertificateChain(entry.storeAlias()) != null) {
                aliases.add(entry.uniqueAlias());
            }
        }
        return aliases.toArray(String[]::new);
    }

    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return new String[0];
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers,
                                    Socket socket) {
        return null;
    }

    @Override
    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers,
                                          SSLEngine engine) {
        return null;
    }
}
