package com.jujin.freeway.boot;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.commons.config.ConfigSpec;

import java.util.List;
import java.util.Map;

/** Application configuration facade: flat key-value lookups, active profiles, and an unmodifiable snapshot. */
public interface AppConfig {
    /** Returns the configured value for {@code key}, or {@code null} if absent. */
    String get(String key);

    /**
     * Returns the typed value for {@code key}: parsed from the raw string
     * with the key's parser, or the key's default when absent/blank. Errors
     * (missing required key, malformed value) are reported by the key itself
     * with the key name in the message.
     *
     * <p>Specs created without a per-key parser ({@code ConfigSpec.of(key,
     * type, default)}) are resolved with the default {@link Coercer} — the
     * container Coercer's built-in conversions apply; user-registered
     * {@code CoerceRule}s require the container coercer via
     * {@code parse(raw, Coercer)}.</p>
     */
    default <T> T get(ConfigSpec<T> key) {
        if (key.parser() != null) {
            return key.parse(get(key.key()));
        }
        return key.parse(get(key.key()), new CoercerDefault());
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
