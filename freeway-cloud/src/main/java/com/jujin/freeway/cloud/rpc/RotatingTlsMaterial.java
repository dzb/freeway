package com.jujin.freeway.cloud.rpc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import com.jujin.freeway.commons.security.TlsStores;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The outbound TLS key material as a <em>rotating</em> fact: the files behind
 * it change, the {@link javax.net.ssl.SSLContext} does not.
 *
 * <p>This is what makes hot rotation possible at all: the JDK's
 * {@code HttpClient} captures its {@code SSLContext} when it is built, so a new
 * context would mean a new client — and a new client means re-opening the pool
 * and the in-flight settlement of the RPC client and the mesh dialer. Keeping
 * the context and swapping what it delegates to is the one shape that rotates
 * without that. The inbound side (the engine's {@code SslReloader}) can afford
 * the other shape because it owns the accept loop and reads the context per
 * handshake; outbound cannot.</p>
 *
 * <p>Refresh is <b>lazy and throttled</b> (default one second, like the secret
 * store's file checks): the material is re-read when a handshake asks for it and
 * the interval has elapsed, so there is no background thread and a process with
 * no traffic pays nothing. A file that cannot be read — dropped, half-written,
 * mid-replace — keeps the previous material and logs, the same rule the secret
 * store follows: a rotation in progress must not take the process down.</p>
 */
final class RotatingTlsMaterial {

    private static final Logger LOG = LoggerFactory.getLogger(RotatingTlsMaterial.class);

    /** One consistent read of both files: the managers a handshake may use. */
    record Material(KeyManager[] keyManagers, TrustManager[] trustManagers, String stamp) {}

    private final Path keyStorePath;
    private final String keyStorePassword;
    private final Path trustStorePath;      // nullable: JDK default anchors
    private final String trustStorePassword;
    private final long intervalMillis;
    private final Object refreshLock = new Object();

    private volatile Material material;
    private volatile long lastCheckNanos;

    RotatingTlsMaterial(Path keyStorePath, String keyStorePassword,
                        Path trustStorePath, String trustStorePassword,
                        Duration reloadInterval) {
        this.keyStorePath = keyStorePath;
        this.keyStorePassword = keyStorePassword;
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.intervalMillis = reloadInterval == null ? 0 : Math.max(0, reloadInterval.toMillis());
        // Load once eagerly so a misconfigured path fails at startup, not at the
        // first call — the same fail-fast the static path has.
        try {
            this.material = load();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException(
                "Failed to build TLS context from " + keyStorePath, e);
        }
    }

    /** True when rotation is configured (a zero interval keeps the static path). */
    boolean rotating() {
        return intervalMillis > 0;
    }

    /** The current material, refreshed at most once per interval. */
    Material current() {
        Material snapshot = material;
        if (intervalMillis <= 0) {
            return snapshot;
        }
        long now = System.nanoTime();
        if (now - lastCheckNanos < intervalMillis * 1_000_000L) {
            return snapshot;
        }
        synchronized (refreshLock) {
            // Another handshake may have refreshed while this one waited.
            if (System.nanoTime() - lastCheckNanos < intervalMillis * 1_000_000L) {
                return material;
            }
            lastCheckNanos = System.nanoTime();
            try {
                String stamp = stamp();
                if (!stamp.equals(material.stamp())) {
                    Material fresh = load();
                    material = fresh;
                    LOG.info("Outbound TLS material reloaded from {}", keyStorePath);
                }
            } catch (GeneralSecurityException | IOException e) {
                // Mid-replace, unreadable, or malformed: keep serving with the
                // material that worked, and try again next interval.
                LOG.warn("Outbound TLS reload skipped, keeping the previous material: {}",
                    e.getMessage());
            }
            return material;
        }
    }

    /**
     * File identity, not content: size plus modification time is what a rotation
     * changes (an atomic replace always does, which is also how these files are
     * written in practice). Reading both files' bytes every second would make a
     * cheap check expensive.
     */
    private String stamp() throws IOException {
        StringBuilder stamp = new StringBuilder();
        stamp.append(stampOf(keyStorePath)).append('|');
        stamp.append(trustStorePath == null ? "jdk-default" : stampOf(trustStorePath));
        return stamp.toString();
    }

    private static String stampOf(Path path) throws IOException {
        FileTime modified = Files.getLastModifiedTime(path);
        // Nanosecond precision where the filesystem has it: a millisecond stamp
        // cannot see two writes inside the same millisecond, and a coarse
        // timestamp would miss a rotation that happened between two checks.
        return Files.size(path) + "@" + modified.to(TimeUnit.NANOSECONDS);
    }

    private Material load() throws GeneralSecurityException, IOException {
        KeyStore keyStore = TlsStores.load(keyStorePath, keyStorePassword);
        KeyManagerFactory kmf =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, TlsStores.passwordChars(keyStorePassword));

        TrustManagerFactory tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (trustStorePath != null) {
            tmf.init(TlsStores.load(trustStorePath, trustStorePassword));
        } else {
            tmf.init((KeyStore) null); // JDK default trust anchors
        }
        return new Material(kmf.getKeyManagers(), tmf.getTrustManagers(), stamp());
    }

    /** Convenience for the managers: the first delegate of the wanted family. */
    static <T> T firstOf(Object[] candidates, Class<T> type) {
        for (Object candidate : List.of(candidates)) {
            if (type.isInstance(candidate)) {
                return type.cast(candidate);
            }
        }
        return null;
    }
}
