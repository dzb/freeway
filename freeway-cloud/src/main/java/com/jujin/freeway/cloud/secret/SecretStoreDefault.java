package com.jujin.freeway.cloud.secret;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 */
public final class SecretStoreDefault implements SecretStore {

    private static final Logger LOG = LoggerFactory.getLogger(SecretStoreDefault.class);

    private final Path file;
    private volatile Map<String, String> cached = Map.of();

    public SecretStoreDefault(Path file) {
        this.file = file.toAbsolutePath().normalize();
        reload();
    }

    @Override
    public Optional<String> get(String key) {
        String env = System.getenv(envKey(key));
        if (env != null) {
            return Optional.of(env);
        }
        return Optional.ofNullable(cached.get(key));
    }

    /** Re-reads the secrets file (called on startup; rotation handling is TTL-based in adapters). */
    public void reload() {
        if (!Files.isRegularFile(file)) {
            cached = Map.of();
            return;
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
        cached = Map.copyOf(next);
    }

    static String envKey(String key) {
        return key.replace('.', '_').toUpperCase(java.util.Locale.ROOT);
    }
}
