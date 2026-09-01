package com.jujin.freeway.boot;

import com.jujin.freeway.boot.internal.BootConfigProvider;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The framework's standard {@link AppConfig}: the boot cascade with a
 * hot-reloadable file tier. One implementation covers both the static form
 * (a plain merged map — the simple constructor) and the dynamic form
 * (tiered sources + filesystem watching).
 *
 * <p><b>Tiers.</b> CLI arguments and environment variables are fixed at
 * startup; the file tier merges the classpath baseline (packaged
 * {@code application*.properties/json}, static — a jar cannot change) with
 * filesystem override files (the same standard names in the working
 * directory, plus any files listed in the {@code freeway.config.file} system
 * property, comma-separated). Filesystem files win over the classpath
 * baseline; later files win over earlier ones. The simple two-argument
 * constructor is the static form: the merged map becomes the file tier with
 * no overrides and no watcher.
 *
 * <p><b>Hot reload.</b> When override files exist, a daemon
 * {@link WatchService} thread watches their directories and swaps the
 * file-tier snapshot on create/modify/delete — a deleted file contributes
 * nothing, so its values fall back to the baseline. Reload is pull-based:
 * the file tier's {@link SymbolProvider} reads the live snapshot on every
 * lookup, so {@code @Value}/{@code @Symbol} re-resolution sees new values
 * through the symbol chain with no push API. The {@code freeway.profile}
 * key and the active profile set stay startup-static.
 */
public final class AppConfigDefault implements AppConfig, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigDefault.class);

    private final Map<String, String> cli;
    private final Map<String, String> environment;
    /** Classpath baseline — static by nature (a packaged jar cannot change). */
    private final Map<String, String> baseline;
    /** Ordered filesystem override files (later wins over earlier). */
    private final List<Path> overrideFiles;
    private final List<String> profiles;

    /** Current file tier: baseline merged with the override snapshots. */
    private volatile Map<String, String> fileTier;
    /** Current merged view: cli + env + fileTier (env outranks files, cli outranks env). */
    private volatile Map<String, String> merged;

    private final WatchService watchService;
    private final Thread watcher;

    /**
     * Static form: {@code values} is the whole config (it becomes the file
     * tier), no CLI/env tiers and no filesystem watching. Usable standalone
     * for tests and custom {@link ConfigLoader} implementations.
     *
     * <p>Custom loaders may include null entries to mean "unset" — they are
     * skipped instead of failing with an opaque NPE from {@code Map.copyOf}.
     */
    public AppConfigDefault(Map<String, String> values, List<String> profiles) {
        this(Map.of(), Map.of(), cleaned(values), List.of(), profiles);
    }

    /**
     * Tiered form: CLI arguments and environment variables are fixed at
     * startup; {@code baseline} plus {@code overrideFiles} form the file
     * tier, which is watched and re-read on change.
     *
     * @param overrideFiles ordered filesystem files merged over the baseline
     */
    public AppConfigDefault(
        Map<String, String> cli,
        Map<String, String> environment,
        Map<String, String> baseline,
        List<Path> overrideFiles,
        List<String> profiles
    ) {
        this.cli = Map.copyOf(Objects.requireNonNull(cli, "cli"));
        this.environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        this.baseline = Map.copyOf(Objects.requireNonNull(baseline, "baseline"));
        this.overrideFiles = List.copyOf(Objects.requireNonNull(overrideFiles, "overrideFiles"));
        this.profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        reload();
        // Watch every override file's directory; events are filtered by
        // filename so unrelated writes in the same directory are ignored.
        WatchService ws = null;
        Thread thread = null;
        try {
            ws = FileSystems.getDefault().newWatchService();
            boolean watching = false;
            for (Path file : this.overrideFiles) {
                Path dir = file.toAbsolutePath().getParent();
                if (dir != null && Files.isDirectory(dir)) {
                    dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                    watching = true;
                }
            }
            if (watching) {
                thread = new Thread(this::watchLoop, "freeway-config-watch");
                thread.setDaemon(true);
            }
        } catch (IOException e) {
            LOG.warn("Config watch disabled: {}", e.getMessage());
            ws = null;
        }
        this.watchService = ws;
        this.watcher = thread;
        if (thread != null) {
            thread.start();
        }
    }

    @Override
    public String get(String key) {
        return merged.get(key);
    }

    @Override
    public Map<String, String> asMap() {
        return Map.copyOf(merged);
    }

    @Override
    public List<String> profiles() {
        return profiles;
    }

    @Override
    public List<SymbolProvider> symbolProviders() {
        return List.of(
            // One source per tier with a declared order; the files source
            // re-reads the live snapshot on every lookup — that is how hot
            // reload reaches the symbol chain.
            new BootConfigProvider(() -> cli, SymbolProvider.TIER_CLI),
            new BootConfigProvider(() -> environment, SymbolProvider.TIER_ENV),
            new BootConfigProvider(this::fileTier, SymbolProvider.TIER_FILES));
    }

    /** Current file tier (baseline + overrides) — read by the files source
     *  on every lookup, which is how hot reload reaches the symbol chain. */
    private Map<String, String> fileTier() {
        return fileTier;
    }

    /** Re-reads every override file and swaps both snapshots atomically. */
    public void reload() {
        Map<String, String> files = new LinkedHashMap<>(baseline);
        for (Path file : overrideFiles) {
            readOverride(file).forEach(files::put); // later files win
        }
        Map<String, String> tier = Map.copyOf(files);
        Map<String, String> all = new LinkedHashMap<>(tier);
        all.putAll(environment);
        all.putAll(cli);
        fileTier = tier;
        merged = Map.copyOf(all);
    }

    /** The file's properties; a missing/unreadable file contributes nothing. */
    private static Map<String, String> readOverride(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            LOG.warn("Failed to read config file {}: {}", file, e.getMessage());
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        props.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
        return map;
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
        return Map.copyOf(cleaned);
    }

    @Override
    public void close() {
        if (watcher != null) {
            watcher.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.debug("WatchService close failed", e);
            }
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // close() interrupts — the only expected exit
            } catch (Exception terminal) {
                return; // WatchService closed underneath us
            }
            try {
                boolean reloaded = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    if (!(ctx instanceof Path changed)) {
                        continue;
                    }
                    Path dir = (Path) key.watchable();
                    Path absolute = dir.resolve(changed).toAbsolutePath().normalize();
                    for (Path file : overrideFiles) {
                        if (file.toAbsolutePath().normalize().equals(absolute)) {
                            reloaded = true;
                            break;
                        }
                    }
                }
                if (reloaded) {
                    reload();
                }
                key.reset();
            } catch (RuntimeException e) {
                // One failed iteration must not silently end hot reload.
                LOG.warn("Config watch iteration failed, continuing: {}", e.getMessage());
            }
        }
    }
}
