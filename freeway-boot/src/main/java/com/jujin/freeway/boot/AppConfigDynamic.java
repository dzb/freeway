package com.jujin.freeway.boot;

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
 * hot-reloadable file tier.
 *
 * <p><b>Tiers.</b> CLI arguments and environment variables are fixed at
 * startup; the file tier merges the classpath baseline (packaged
 * {@code application*.properties/json}, static — a jar cannot change) with
 * filesystem override files (the same standard names in the working
 * directory, plus any files listed in the {@code freeway.config.file} system
 * property, comma-separated). Filesystem files win over the classpath
 * baseline; later files win over earlier ones.
 *
 * <p><b>Hot reload.</b> A daemon {@link WatchService} thread watches every
 * override file's directory and swaps the file-tier snapshot on
 * create/modify/delete — a deleted file contributes nothing, so its values
 * fall back to the baseline. Reload is pull-based: the file tier's
 * {@link AppConfig.ConfigLayer} returns a fresh snapshot on every read, so
 * {@code @Value}/{@code @Symbol} re-resolution sees new values through the
 * symbol chain with no push API. The {@code freeway.profile} key and the
 * active profile set stay startup-static.
 *
 * <p>When no override file exists (the common case), the file tier is the
 * classpath baseline alone and behavior is identical to a static config.
 */
public final class AppConfigDynamic implements AppConfig, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppConfigDynamic.class);

    /** Symbol-layer names, consumed by {@code BootConfigModule} to map tiers
     *  to declared {@code SymbolProvider} orders. */
    public static final String NAME_CLI = "cli";
    public static final String NAME_ENV = "env";
    public static final String NAME_FILES = "files";

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

    /** @param overrideFiles ordered filesystem files merged over the baseline */
    public AppConfigDynamic(
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
    public List<AppConfig.ConfigLayer> layers() {
        return List.of(
            new AppConfig.ConfigLayer(NAME_CLI, cli),
            new AppConfig.ConfigLayer(NAME_ENV, environment),
            // The live tier: every read returns the current snapshot, which
            // is how hot reload reaches the symbol chain.
            new AppConfig.ConfigLayer(NAME_FILES, this::fileTier));
    }

    /** Current file tier (baseline + overrides) — read by the symbol provider. */
    public Map<String, String> fileTier() {
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

    /** @return the list of filesystem override files this config watches */
    List<Path> overrideFiles() {
        return overrideFiles;
    }
}
