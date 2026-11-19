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
 * than serving the previous generation for another moment. A rotation that
 * parses to zero entries (an empty file, a half-written projected volume, a
 * file of malformed lines) is treated the same way: the previous generation
 * stays and the reason is logged, because {@link Properties} silently drops
 * lines it cannot parse.</p>
 *
 * <p>Every skipped rotation reports one of four reasons — {@code file-missing},
 * {@code stat-failed}, {@code read-failed}, {@code empty-file} — together with
 * the path, the underlying cause when there is one, and how many previously
 * loaded values were kept. The last reason is also readable through the
 * package-private {@link #lastSkippedRotation()} seam for tests.</p>
 */
public final class SecretStoreDefault implements SecretStore {

    private static final Logger LOG = LoggerFactory.getLogger(SecretStoreDefault.class);

    /** Stat at most this often; a rotation is visible one lookup later. */
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(1);

    /** Why the last rotation attempt was skipped, or null after a clean load. */
    private volatile String lastSkippedRotation;
    /** The reason+detail already warned about, so a persistent failure logs once. */
    private volatile String lastSkipReported;

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
    public Optional<String> find(String key) {
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
                lastSkippedRotation = "file-missing";
                if (!keepOnFailure) {
                    cached = Map.of();
                } else {
                    skipRotation("file-missing", file + " is missing");
                }
                return;
            }
            modified = Files.getLastModifiedTime(file);
            size = Files.size(file);
        } catch (IOException e) {
            skipRotation("stat-failed", file + ": " + e.getMessage());
            return;
        }
        if (modified.equals(lastModified) && size == lastSize) {
            return; // unchanged since the last successful read
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            skipRotation("read-failed", file + ": " + e.getMessage());
            return;
        }
        Map<String, String> next = new java.util.HashMap<>();
        props.forEach((k, v) -> next.put(String.valueOf(k), String.valueOf(v)));
        Map<String, String> previous = cached;
        if (keepOnFailure && next.isEmpty() && !previous.isEmpty()) {
            // A projected-volume swap can expose an empty or half-written
            // file, and Properties drops unparsable lines without a word, so
            // "parsed nothing" is the only signal there is. Never empty a
            // store that already had values. The stamp is still remembered:
            // re-reading the same bad generation on every lookup would only
            // repeat the warning.
            lastModified = modified;
            lastSize = size;
            skipRotation("empty-file",
                file + " parsed to 0 entries (empty, truncated or malformed)");
            return;
        }
        cached = Map.copyOf(next);
        lastModified = modified;
        lastSize = size;
        lastSkippedRotation = null;
        lastSkipReported = null;
        if (keepOnFailure && !previous.isEmpty()) {
            LOG.info("Reloaded {} secret(s) from {}", cached.size(), file);
        }
    }

    /**
     * Keeps the generation already loaded and records why. A persistent
     * condition (a file that stays missing, a permission that stays wrong) is
     * warned about once; the throttled lookup path would otherwise repeat it
     * every check interval.
     */
    private void skipRotation(String reason, String detail) {
        lastSkippedRotation = reason;
        var report = reason + "|" + detail;
        if (report.equals(lastSkipReported)) {
            return;
        }
        lastSkipReported = report;
        if (cached.isEmpty()) {
            LOG.warn("Secrets unavailable ({}): {} — the store stays empty",
                reason, detail);
        } else {
            LOG.warn(
                "Secrets rotation skipped ({}): {} — keeping {} previously loaded value(s)",
                reason, detail, cached.size());
        }
    }

    /** Test seam: the reason recorded by the last refused load or rotation. */
    String lastSkippedRotation() {
        return lastSkippedRotation;
    }

    static String envKey(String key) {
        return key.replace('.', '_').toUpperCase(java.util.Locale.ROOT);
    }
}
