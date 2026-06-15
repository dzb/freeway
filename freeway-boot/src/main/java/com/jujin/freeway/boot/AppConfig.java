package com.jujin.freeway.boot;

import java.util.List;
import java.util.Map;

public interface AppConfig {
    String get(String key);

    List<String> profiles();
    /**
     * Returns the full configuration as an unmodifiable map.
     * Implementations must return a snapshot — mutations to the returned map
     * are not supported and modifying the source after this call must not
     * affect the returned map.
     */
    Map<String, String> asMap();
}
