package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;

import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The framework's standard {@link AppConfig}: the boot cascade with a
 * hot-reloadable file tier.
 *
 * <p><b>Tiers.</b> CLI arguments and environment variables are fixed at
 * startup; the file tier merges the classpath baseline (packaged
 * {@code application*.properties/json}, static — a jar cannot change) with
 * filesystem override files (the same standard names in the working
 * directory, plus any files listed in the {@code freeway.config.file}
 * bootstrap key, comma-separated). Filesystem files win over the classpath
 * baseline; later files win over earlier ones. {@link #of} is the static form:
 * the given map becomes the file tier with no overrides and no watcher.
 *
 * <p><b>Hot reload.</b> When override files exist, {@link ConfigFileWatcher}
 * watches their directories and swaps the file tier on create/modify/delete —
 * a deleted file contributes nothing, so its values fall back to the
 * baseline. Reload is pull-based: the file tier's {@link SymbolProvider}
 * reads the live map on every lookup, so {@code @Value}/{@code @Symbol}
 * re-resolution sees new values through the symbol chain with no push API.
 * The {@code freeway.profile} key and the active profile set stay
 * startup-static.
 *
 * <p><b>No global state.</b> Everything this class serves arrives through
 * {@link ConfigSources} — the loader reads the JVM and the environment once,
 * at startup. A directly constructed instance therefore cannot be surprised
 * by a system property.
 */
public final class AppConfigDefault implements AppConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigDefault.class);

    private final ConfigSources sources;
    /** Ordered filesystem override files (later wins over earlier). */
    private final List<Path> overrideFiles;

    /** Current file tier: the classpath baseline overlaid with the overrides. */
    private volatile Map<String, String> fileTier;
    /** Hot-reload watcher; null when there is nothing to watch. */
    private final ConfigFileWatcher watcher;

    /**
     * Tiered form: {@code sources} carries the cascade inputs the loader
     * resolved (cli → environment → files, plus the active profiles);
     * {@code sources.files()} overlaid with {@code overrideFiles} forms the
     * file tier, which is watched and re-read on change.
     *
     * @param overrideFiles ordered filesystem files merged over the baseline
     */
    public AppConfigDefault(ConfigSources sources, List<Path> overrideFiles) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.overrideFiles = List.copyOf(Objects.requireNonNull(overrideFiles, "overrideFiles"));
        reload();
        this.watcher = ConfigFileWatcher.start(this.overrideFiles, this::reload);
    }

    /**
     * Static form: {@code values} is the whole config (it becomes the file
     * tier), no CLI/env tiers and no filesystem watching. Usable standalone
     * for tests and custom config sources.
     *
     * <p>Custom loaders may include null entries to mean "unset" — they are
     * skipped instead of failing with an opaque NPE from {@code Map.copyOf}.
     * A null {@code profiles} list is treated as empty.
     */
    public static AppConfigDefault of(Map<String, String> values, List<String> profiles) {
        return new AppConfigDefault(
            new ConfigSources(
                Map.of(),          // no CLI tier
                Map.of(),          // no environment mapping
                cleaned(values),   // the given map IS the file tier
                profiles == null ? List.of() : profiles),
            List.of());
    }

    @Override
    public List<String> profiles() {
        return sources.profiles();
    }

    @Override
    public List<SymbolProvider> providers() {
        return List.of(
            // One source per tier with a declared order; the files source
            // re-reads the live map on every lookup — that is how hot reload
            // reaches the symbol chain.
            SymbolProvider.of(sources::cli, SymbolProvider.TIER_CLI),
            SymbolProvider.of(sources::environment, SymbolProvider.TIER_ENV),
            SymbolProvider.of(() -> fileTier, SymbolProvider.TIER_FILES));
    }

    /** Re-reads every override file over the baseline and swaps the file tier. */
    private void reload() {
        List<Map<String, String>> layers = new ArrayList<>(overrideFiles.size() + 1);
        layers.add(sources.files());
        for (Path file : overrideFiles) {
            layers.add(readOverride(file)); // later files win
        }
        fileTier = ConfigMaps.overlay(layers);
    }

    /**
     * The file's parsed content; a missing/unreadable file contributes
     * nothing. Parsed by the shared {@link ConfigFileReader} — a
     * {@code .json} override file is JSON, everything else properties — so
     * overrides parse identically to the startup cascade.
     *
     * <p>Every filesystem file passes through here — the working-directory
     * base files, the profile variants and the {@code freeway.config.file}
     * extras — so a bootstrap-only key is named whichever of them declares it,
     * and a hot reload that re-reads an offending file warns again: the
     * warning describes the file's current content, not the first time it was
     * seen.
     */
    private static Map<String, String> readOverride(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, String> values = ConfigFileReader.read(file);
            Path name = file.getFileName();
            ConfigLoaderImpl.warnAboutBootstrapKeys(
                name != null ? name.toString() : file.toString(), values);
            return values;
        } catch (IOException e) {
            LOG.warn("Failed to read config file {}: {}", file, e.getMessage());
            return Map.of();
        }
    }

    private static Map<String, String> cleaned(Map<String, String> values) {
        Map<String, String> cleaned = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key != null && value != null) {
                    cleaned.put(key, value);
                }
            });
        }
        return cleaned;
    }

    @Override
    public void close() {
        if (watcher != null) {
            watcher.close();
        }
    }
}
