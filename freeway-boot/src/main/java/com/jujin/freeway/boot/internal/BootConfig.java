package com.jujin.freeway2.boot.internal;

import com.jujin.freeway2.boot.AppConfig;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class BootConfig implements AppConfig {
    private final Map<String, String> values;
    private final List<String> profiles;

    public BootConfig(Map<String, String> values, List<String> profiles) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
        this.profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
    }

    @Override
    public String get(String key) {
        return values.get(key);
    }

    @Override
    public Map<String, String> asMap() {
        return values;
    }

    @Override
    public List<String> profiles() {
        return profiles;
    }
}
