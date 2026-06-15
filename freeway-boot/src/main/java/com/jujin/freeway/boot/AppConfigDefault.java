package com.jujin.freeway.boot;

import java.util.List;
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
        values = Map.copyOf(values);
        profiles = List.copyOf(profiles);
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
