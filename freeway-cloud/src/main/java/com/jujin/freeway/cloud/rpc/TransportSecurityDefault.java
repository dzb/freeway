package com.jujin.freeway.cloud.rpc;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * Default {@link TransportSecurity}: builds an {@link SSLContext} from a
 * PKCS12/JKS keystore (client identity) and optional truststore (peer
 * verification) — both loaded via the JDK only, no third-party crypto.
 *
 * <p>Substitutable from outside: {@link CloudRpcModule} activates it from the
 * {@code freeway.cloud.rpc.tls.*} keys, and an extension module may bind its
 * own {@link TransportSecurity} implementation (e.g. Vault-backed) with
 * {@code .primary()} instead.
 */
public final class TransportSecurityDefault implements TransportSecurity {

    private final SSLContext sslContext;

    private TransportSecurityDefault(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    /**
     * @param keyStorePath         client identity keystore (required)
     * @param keyStorePassword     keystore password
     * @param trustStorePath       truststore for peer verification, or {@code null}
     *                             to trust the JDK default trust anchors
     * @param trustStorePassword   truststore password (ignored when trustStorePath is null)
     */
    public static TransportSecurityDefault fromKeyStore(
        Path keyStorePath, String keyStorePassword,
        Path trustStorePath, String trustStorePassword
    ) {
        return fromKeyStore(keyStorePath, keyStorePassword, trustStorePath, trustStorePassword,
            Duration.ZERO);
    }

    /**
     * @param reloadInterval how often rotated material may be picked up; zero
     *     keeps the resolved-once behaviour (a rotated keystore needs a restart).
     *     A positive interval swaps the key/trust managers for delegates that
     *     re-read the files per handshake — see {@link RotatingTlsMaterial} for
     *     why the context itself cannot be replaced outbound.
     */
    public static TransportSecurityDefault fromKeyStore(
        Path keyStorePath, String keyStorePassword,
        Path trustStorePath, String trustStorePassword,
        Duration reloadInterval
    ) {
        RotatingTlsMaterial rotating = new RotatingTlsMaterial(
            keyStorePath, keyStorePassword, trustStorePath, trustStorePassword, reloadInterval);
        if (rotating.rotating()) {
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(new KeyManager[] {new ReloadingKeyManager(rotating)},
                    new TrustManager[] {new ReloadingTrustManager(rotating)}, null);
                return new TransportSecurityDefault(context);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException(
                    "Failed to build TLS context from " + keyStorePath, e);
            }
        }
        return staticContext(rotating.current(), keyStorePath);
    }

    private static TransportSecurityDefault staticContext(
            RotatingTlsMaterial.Material material, Path keyStorePath) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(material.keyManagers(), material.trustManagers(), null);
            return new TransportSecurityDefault(context);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException(
                "Failed to build TLS context from " + keyStorePath, e);
        }
    }

    @Override
    public SSLContext sslContext() {
        return sslContext;
    }
}
