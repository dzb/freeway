package com.jujin.freeway.boot;

import java.util.List;
import java.util.Map;

/** Application configuration facade: flat key-value lookups, active profiles, and an unmodifiable snapshot. */
public interface AppConfig {
    /** Returns the configured value for {@code key}, or {@code null} if absent. */
    String get(String key);

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
