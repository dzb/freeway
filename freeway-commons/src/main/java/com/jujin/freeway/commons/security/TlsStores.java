package com.jujin.freeway.commons.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * The one place that knows how to load a TLS key/trust store.
 *
 * <p>Both TLS paths — the HTTP server's keystore (with its SNI directory) and the
 * cloud RPC client's rotating material — read PKCS#12 and JKS files with the same
 * two rules, and the rules are here rather than in each caller:</p>
 *
 * <ul>
 *   <li><b>Format.</b> An explicit type is honored when the caller knows it (the
 *       server infers it from the file extension). With no type, PKCS#12 is tried
 *       first and JKS is the fallback: a JKS file fails while being parsed as
 *       PKCS#12, so the fallback is not a guess the caller has to make.</li>
 *   <li><b>Empty password.</b> A {@code null} or empty password is handed to
 *       {@link KeyStore#load} as {@code null} — an empty-password PKCS#12 store
 *       rejects an empty {@code char[]}, while both formats accept {@code null}
 *       as "no password".</li>
 * </ul>
 */
public final class TlsStores {

    private TlsStores() {}

    /**
     * Loads a store whose format is not known in advance: PKCS#12 first, then JKS.
     *
     * @param path     the store file
     * @param password the store password; {@code null}/empty means "no password"
     */
    public static KeyStore load(Path path, String password)
            throws GeneralSecurityException, IOException {
        try {
            return load(path, "PKCS12", password);
        } catch (IOException pkcs12Failed) {
            try {
                return load(path, "JKS", password);
            } catch (IOException jksFailed) {
                jksFailed.addSuppressed(pkcs12Failed);
                throw jksFailed;
            }
        }
    }

    /**
     * Loads a store of the given type.
     *
     * @param path     the store file
     * @param type     the {@link KeyStore} type ({@code PKCS12}, {@code JKS}, …)
     * @param password the store password; {@code null}/empty means "no password"
     */
    public static KeyStore load(Path path, String type, String password)
            throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            store.load(in, passwordChars(password));
        }
        return store;
    }

    /**
     * The one password rule: {@code null} or empty means "no password", and
     * {@link KeyStore#load} (and the key-manager factories) expect {@code null} in
     * that case, not an empty array.
     */
    public static char[] passwordChars(String password) {
        return password == null || password.isEmpty() ? null : password.toCharArray();
    }
}
