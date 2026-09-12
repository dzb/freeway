package com.jujin.freeway.cloud.secret;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Env/file-backed {@link SecretStore}. Lookup order: environment variable
 * first (key uppercased with {@code '.'} → {@code '_'}, e.g.
 * {@code db.password} → {@code DB_PASSWORD}), then the secrets file
 * ({@code key=value} properties, UTF-8). No fallback defaults by design.
 *
 * <p><b>Rotation:</b> the file is re-read when its size or modification time
 * changed, so replacing a mounted secret (the Kubernetes projected-volume
 * swap, a rewritten properties file) takes effect without a restart. The check
 * is throttled — a lookup must not stat the filesystem on every call — and it
 * is fail-safe: an unreadable or momentarily absent file keeps the values
 * already loaded and warns, because dropping live secrets mid-rotation is worse
 * than serving the previous generation for another moment.</p>
 */
public final class SecretStoreDefault implements SecretStore {

    private static final Logger LOG = LoggerFactory.getLogger(SecretStoreDefault.class);

    /** Stat at most this often; a rotation is visible one lookup later. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(1);

    private final Path file;
    private final long checkIntervalNanos;
    private volatile Map<String, String> cached = Map.of();
    private volatile long lastCheckNanos;
    /** Full-precision stamp of the last successful read: a same-size rewrite
     *  inside one millisecond must still count as a change. */
    private volatile FileTime lastModified;
    private volatile long lastSize = -1;

    public SecretStoreDefault(Path file) {
        this(file, CHECK_INTERVAL);
    }

    /** Test seam: {@code Duration.ZERO} re-checks on every lookup. */
    SecretStoreDefault(Path file, Duration checkInterval) {
        this.file = file.toAbsolutePath().normalize();
        this.checkIntervalNanos = checkInterval.toNanos();
        reload(false);
    }

    @Override
    public Optional<String> get(String key) {
        String env = System.getenv(envKey(key));
        if (env != null) {
            return Optional.of(env);
        }
        refreshIfChanged();
        return Optional.ofNullable(cached.get(key));
    }

    /** Re-reads the file when it changed, at most once per check interval. */
    private void refreshIfChanged() {
        if (checkIntervalNanos > 0
                && System.nanoTime() - lastCheckNanos < checkIntervalNanos) {
            return;
        }
        reload(true);
    }

    /**
     * @param keepOnFailure whether an unreadable file leaves the previously
     *                      loaded values in place (true after startup: a
     *                      rotation must never empty the store)
     */
    private void reload(boolean keepOnFailure) {
        lastCheckNanos = System.nanoTime();
        FileTime modified;
        long size;
        try {
            if (!Files.isRegularFile(file)) {
                // Forget the stamp: whatever appears here next counts as new,
                // even if it looks identical to what was loaded before.
                lastModified = null;
                lastSize = -1;
                if (!keepOnFailure) {
                    cached = Map.of();
                } else if (!cached.isEmpty()) {
                    LOG.warn("Secrets file {} is gone — keeping the {} value(s) already loaded",
                        file, cached.size());
                }
                return;
            }
            modified = Files.getLastModifiedTime(file);
            size = Files.size(file);
        } catch (IOException e) {
            LOG.warn("Could not stat secrets file {}: {}", file, e.getMessage());
            return;
        }
        if (modified.equals(lastModified) && size == lastSize) {
            return; // unchanged since the last successful read
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            LOG.warn("Failed to read secrets file {}: {}", file, e.getMessage());
            return;
        }
        Map<String, String> next = new java.util.HashMap<>();
        props.forEach((k, v) -> next.put(String.valueOf(k), String.valueOf(v)));
        Map<String, String> previous = cached;
        cached = Map.copyOf(next);
        lastModified = modified;
        lastSize = size;
        if (keepOnFailure && !previous.isEmpty()) {
            LOG.info("Reloaded {} secret(s) from {}", cached.size(), file);
        }
    }

    static String envKey(String key) {
        return key.replace('.', '_').toUpperCase(java.util.Locale.ROOT);
    }
}
