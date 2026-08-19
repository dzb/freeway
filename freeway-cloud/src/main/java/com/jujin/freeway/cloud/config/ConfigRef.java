package com.jujin.freeway.cloud.config;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.coercion.CoercerDefault;

import java.util.Objects;

/**
 * Explicit pull wrapper for a single dynamic config key: {@code get()} reads
 * the latest value each call. No field-auto-refresh magic — consumers opt in
 * by reading through the ref (or via {@code ConfigChangedEvent}).
 *
 * @param <T> value type
 */
public final class ConfigRef<T> {

    private static final Coercer COERCER = new CoercerDefault();

    private final CloudConfig config;
    private final String key;
    private final Class<T> type;
    private final T defaultValue;

    private ConfigRef(CloudConfig config, String key, Class<T> type, T defaultValue) {
        this.config = Objects.requireNonNull(config, "config");
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.defaultValue = defaultValue;
    }

    public static <T> ConfigRef<T> of(CloudConfig config, String key, Class<T> type, T defaultValue) {
        return new ConfigRef<>(config, key, type, defaultValue);
    }

    /** Latest value for the key, coerced to {@code T}, or the default when absent. */
    public T get() {
        return config.get(key)
            .map(raw -> COERCER.coerce(raw, type))
            .orElse(defaultValue);
    }

    public String key() {
        return key;
    }
}
