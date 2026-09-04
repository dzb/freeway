package com.jujin.freeway.cloud.rpc;

import com.jujin.freeway.cloud.rpc.TransportSecurity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * File-backed {@link TransportSecurity}: builds an {@link SSLContext} from a
 * PKCS12/JKS keystore (client identity) and optional truststore (peer
 * verification) — both loaded via the JDK only, no third-party crypto.
 */
public final class TransportSecurityImpl implements TransportSecurity {

    private final SSLContext sslContext;

    private TransportSecurityImpl(SSLContext sslContext) {
        this.sslContext = sslContext;
    }

    /**
     * @param keyStorePath         client identity keystore (required)
     * @param keyStorePassword     keystore password
     * @param trustStorePath       truststore for peer verification, or {@code null}
     *                             to trust the JDK default trust anchors
     * @param trustStorePassword   truststore password (ignored when trustStorePath is null)
     */
    public static TransportSecurityImpl fromKeyStore(
        Path keyStorePath, String keyStorePassword,
        Path trustStorePath, String trustStorePassword
    ) {
        try {
            KeyStore keyStore = load(keyStorePath, keyStorePassword);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, passwordChars(keyStorePassword));

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            if (trustStorePath != null) {
                tmf.init(load(trustStorePath, trustStorePassword));
            } else {
                tmf.init((KeyStore) null); // JDK default trust anchors
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return new TransportSecurityImpl(context);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("Failed to build TLS context from " + keyStorePath, e);
        }
    }

    @Override
    public SSLContext sslContext() {
        return sslContext;
    }

    private static KeyStore load(Path path, String password) throws GeneralSecurityException, IOException {
        // The javadoc promises PKCS12/JKS. A JKS file throws while being parsed
        // as PKCS12, so retry as JKS before giving up rather than only ever
        // accepting one format.
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, passwordChars(password));
            return store;
        } catch (IOException pkcs12Failed) {
            KeyStore jks = KeyStore.getInstance("JKS");
            try (InputStream in = Files.newInputStream(path)) {
                jks.load(in, passwordChars(password));
                return jks;
            } catch (IOException jksFailed) {
                jksFailed.addSuppressed(pkcs12Failed);
                throw jksFailed;
            }
        }
    }

    private static char[] passwordChars(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }
}
