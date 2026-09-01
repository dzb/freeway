package com.jujin.freeway.cloud.secret;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Secret source. Deliberately separate from the application config
 * ({@code AppConfig}): no {@code asMap()} (secrets must never be
 * bulk-exposed), no fallback to local defaults (secrets must be explicitly
 * configured), TTL-style caching for rotation support.
 */
public interface SecretStore {

    /** The secret for {@code key}, if configured. */
    Optional<String> get(String key);

    default Optional<byte[]> getBytes(String key) {
        return get(key).map(s -> s.getBytes(StandardCharsets.UTF_8));
    }
}
