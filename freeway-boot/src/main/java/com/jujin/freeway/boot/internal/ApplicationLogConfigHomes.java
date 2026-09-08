package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.Presets;
import com.jujin.freeway.commons.logging.LogConfigHomes;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The boot-side {@link LogConfigHomes}: exposes the {@code freeway.log.*}
 * subset of the application's main config files through the SAME read the
 * main cascade performs — {@link ConfigLoaderDefault#loadLayers} and its
 * {@link BootConfigLayers#fileBaseline() fileBaseline} — so a log key in
 * {@code application.properties}/{@code application.json} (or a profile
 * variant) resolves identically no matter which cascade reads it, and the
 * file-family knowledge exists exactly once. Also exposes the active
 * environment preset's log subset. Registered via {@code META-INF/services}
 * — ServiceLoader is how the boot layer hands the bootstrap log cascade its
 * application knowledge without inverting a dependency.
 */
public final class ApplicationLogConfigHomes implements LogConfigHomes {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationLogConfigHomes.class);

    private static final String LOG_PREFIX = "freeway.log.";

    /** ServiceLoader instantiates via the public no-arg constructor. */
    public ApplicationLogConfigHomes() {}

    @Override
    public Map<String, String> applicationValues() {
        // The loader's own classpath resolution — the same loader rule the
        // main cascade uses (AppBuilder's resolveClassLoader: TCCL first).
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        ConfigLoaderDefault.BootConfigLayers layers = ConfigLoaderDefault.loadLayers(
            loader != null ? loader : ApplicationLogConfigHomes.class.getClassLoader(),
            new String[0]);
        Map<String, String> values = new LinkedHashMap<>();
        layers.fileBaseline().forEach((key, value) -> {
            if (key.startsWith(LOG_PREFIX)) {
                values.put(key, value);
            }
        });
        return values;
    }

    @Override
    public Map<String, String> presetValues() {
        Map<String, String> bundle = Presets.bundle(Presets.declared());
        if (bundle == null) {
            return Map.of();
        }
        Map<String, String> logValues = new LinkedHashMap<>();
        bundle.forEach((key, value) -> {
            if (key.startsWith(LOG_PREFIX)) {
                logValues.put(key, value);
            }
        });
        return logValues;
    }
}
