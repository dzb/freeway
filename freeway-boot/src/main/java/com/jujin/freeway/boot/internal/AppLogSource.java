package com.jujin.freeway.boot.internal;

import com.jujin.freeway.commons.logging.LogConfigSource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The boot-side {@link LogConfigSource}: exposes the {@code freeway.log.*}
 * subset of the application's classpath config files through the SAME read the
 * main cascade performs — {@link ConfigLoaderImpl#loadLayers} and the
 * {@code files} source it returns — so a log key in {@code application.properties}/
 * {@code application.json} (or a profile variant) is parsed by one shared
 * implementation and the file-family knowledge exists exactly once.
 *
 * <p>The boundary is the classpath baseline: filesystem overrides and their
 * hot reload deliberately stay out, so a {@code freeway.log.*} key set only in
 * a working-directory file reaches the main cascade but not the bootstrap log
 * configuration.
 *
 * <p>Registered via {@code META-INF/services} — ServiceLoader is how the boot
 * layer hands the bootstrap log cascade its application knowledge without
 * inverting a dependency.
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
        return logSubset(sources.files());
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
