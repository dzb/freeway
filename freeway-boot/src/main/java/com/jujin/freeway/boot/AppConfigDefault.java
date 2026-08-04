package com.jujin.freeway.boot;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link AppConfig} implementation backed by a flat string map.
 * <p>
 * Immutable — values and profiles are defensively copied on construction.
 * Usable standalone for tests and custom {@link ConfigLoader} implementations.
 */
public record AppConfigDefault(
    Map<String, String> values,
    List<String> profiles
) implements AppConfig {
    public AppConfigDefault {
        // Custom loaders may include null entries to mean "unset" — skip them
        // instead of failing with an opaque NPE from Map.copyOf.
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    cleaned.put(key, value);
                }
            });
        }
        values = Map.copyOf(cleaned);
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    @Override
    public String get(String key) {
        return values.get(key);
    }

    @Override
    public Map<String, String> asMap() {
        return values;
    }
}
