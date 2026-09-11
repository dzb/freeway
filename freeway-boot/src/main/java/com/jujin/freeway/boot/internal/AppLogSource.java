package com.jujin.freeway.boot.internal;

import com.jujin.freeway.commons.logging.LogConfigSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The boot-side {@link LogConfigSource}: exposes the {@code freeway.log.*}
 * subset of the application's main config files through the SAME read the
 * main cascade performs — {@link ConfigLoaderImpl#loadLayers} and the
 * {@code files} source it returns — so a log key in {@code application.properties}/
 * {@code application.json} (or a profile variant) resolves identically no
 * matter which cascade reads it, and the file-family knowledge exists exactly
 * once. Also merges in the active environment preset's log subset (ranked
 * below the files — the preset fills only what they did not set). Registered
 * via {@code META-INF/services} — ServiceLoader is how the boot layer hands
 * the bootstrap log cascade its application knowledge without inverting a
 * dependency.
 */
public final class AppLogSource implements LogConfigSource {

    private static final String LOG_PREFIX = "freeway.log.";

    /** ServiceLoader instantiates via the public no-arg constructor. */
    public AppLogSource() {}

    @Override
    public Map<String, String> values() {
        // The loader's own classpath resolution — the same loader rule the
        // main cascade uses (AppBuilder's resolveClassLoader: TCCL first).
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ConfigSources sources = ConfigLoaderImpl.loadLayers(
            loader != null ? loader : AppLogSource.class.getClassLoader(),
            new String[0]);
        // The same precedence the symbol chain applies: the preset fills only
        // what the file baseline did not set.
        return ConfigMaps.overlay(List.of(
            logSubset(sources.preset()),
            logSubset(sources.files())));
    }

    /** The {@code freeway.log.*} subset of {@code values}. */
    private static Map<String, String> logSubset(Map<String, String> values) {
        Map<String, String> subset = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(LOG_PREFIX)) {
                subset.put(key, value);
            }
        });
        return subset;
    }
}
