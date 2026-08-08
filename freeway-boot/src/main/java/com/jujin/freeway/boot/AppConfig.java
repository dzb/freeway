package com.jujin.freeway.boot;

import java.util.List;
import java.util.Map;

/** Application configuration facade: flat key-value lookups, active profiles, and an unmodifiable snapshot. */
public interface AppConfig {
    /** Returns the configured value for {@code key}, or {@code null} if absent. */
    String get(String key);

    /**
     * Returns the typed value for {@code key}: parsed from the raw string
     * with the key's parser, or the key's default when absent/blank. A
     * malformed raw value throws {@link IllegalArgumentException} naming the
     * key and value.
     */
    default <T> T get(ConfigKey<T> key) {
        String raw = get(key.key());
        try {
            return key.parse(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Invalid value for config key '" + key.key() + "': "
                    + (raw == null ? "null" : "'" + raw + "'"),
                ex
            );
        }
    }

    /** Returns the active profiles in priority order, as an unmodifiable list. */
    List<String> profiles();

    /**
     * Returns the full configuration as an unmodifiable map.
     * Implementations must return a snapshot — mutations to the returned map
     * are not supported and modifying the source after this call must not
     * affect the returned map.
     */
    Map<String, String> asMap();
}
