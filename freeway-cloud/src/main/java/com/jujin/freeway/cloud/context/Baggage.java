package com.jujin.freeway.cloud.context;

import java.util.Map;

/**
 * Application-owned key-value baggage propagated across service boundaries.
 * Values are strings; typed access is the caller's concern.
 */
public record Baggage(Map<String, String> values) {

    public Baggage {
        values = Map.copyOf(values);
    }

    public static Baggage of(Map<String, String> values) {
        return new Baggage(values);
    }

    public static Baggage empty() {
        return new Baggage(Map.of());
    }

    public String get(String key) {
        return values.get(key);
    }

    public Baggage with(String key, String value) {
        var copy = new java.util.HashMap<>(values);
        copy.put(key, value);
        return new Baggage(copy);
    }
}
