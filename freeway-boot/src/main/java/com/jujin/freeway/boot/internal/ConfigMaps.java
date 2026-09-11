package com.jujin.freeway.boot.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place cascade precedence is applied: the layers are overlaid in list
 * order and a later layer wins on a duplicate key. Every merge in the boot
 * config path — the classpath file baseline, the hot-reload file tier, and the
 * bootstrap log subset — calls this method, so "later wins" is defined once
 * instead of being re-expressed as a {@code putAll} chain at each call site.
 */
final class ConfigMaps {

    private ConfigMaps() {}

    /** Overlays {@code layers} in order (later wins) into an immutable map. */
    static Map<String, String> overlay(List<Map<String, String>> layers) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map<String, String> layer : layers) {
            merged.putAll(layer);
        }
        return Map.copyOf(merged);
    }
}
