package com.jujin.freeway.cloud.rpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Keystore/truststore loading shared by the static and the reloading TLS paths —
 * one place that knows the file formats, so a PKCS12/JKS fallback cannot differ
 * between "at startup" and "on rotation".
 */
final class TlsStores {

    private TlsStores() {}

    /**
     * Loads a PKCS12 or JKS store. A JKS file throws while being parsed as
     * PKCS12, so the JKS attempt is the fallback rather than a choice the
     * caller has to make — the javadoc promises both formats.
     */
    static KeyStore load(Path path, String password) throws GeneralSecurityException, IOException {
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

    static char[] passwordChars(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }
}
