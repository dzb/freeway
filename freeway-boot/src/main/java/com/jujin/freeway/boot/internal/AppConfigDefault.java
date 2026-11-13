package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;

import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /** Standard file family, roles, order and the one file-read implementation. */
    private final ConfigFilePlan filePlan;

    /** Current file tier: the classpath baseline overlaid with the overrides. */
    private volatile Map<String, String> fileTier;
    /** Hot-reload watcher; null when there is nothing to watch. */
    private final ConfigFileWatcher watcher;
    /** {@code key\0earlier\0later} of every duplicate already named. */
    private final Set<String> warnedDuplicates = ConcurrentHashMap.newKeySet();
    /** {@code origin\0key} of every unread log key already named, so a hot
     *  reload does not repeat the same line. */
    private final Set<String> warnedUnreadLogKeys = ConcurrentHashMap.newKeySet();

    /**
     * Tiered form: {@code sources} carries the cascade inputs the loader
     * resolved (cli → environment → files, plus the active profiles);
     * {@code sources.files()} overlaid with the filesystem plan forms the
     * file tier, which is watched and re-read on change.
     *
     * <p>Files given as plain paths play the {@link ConfigFilePlan.Role#BASE}
     * role — the caller supplies the cascade position, not a file name
     * pattern.</p>
     *
     * @param overrideFiles ordered filesystem files merged over the baseline
     */
    public AppConfigDefault(ConfigSources sources, List<Path> overrideFiles) {
        this(sources, ConfigFilePlan.paths(overrideFiles), null);
    }

    /** Package-visible role-preserving form for boot and its tests. */
    AppConfigDefault(ConfigSources sources, ConfigFilePlan filePlan) {
        this(sources, filePlan, null);
    }

    AppConfigDefault(
        ConfigSources sources,
        ConfigFilePlan filePlan,
        ConfigFilePlan.Snapshot initialFiles
    ) {
        this.sources = Objects.requireNonNull(sources, "sources");
        this.filePlan = Objects.requireNonNull(filePlan, "filePlan");
        apply(initialFiles != null ? initialFiles : filePlan.read());
        this.watcher = ConfigFileWatcher.start(filePlan.paths(), this::reload);
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

    /**
     * The declared universe for the unknown-key check: CLI, environment and
     * the merged file tier (baseline overlaid with overrides — what the chain
     * actually answers from). A snapshot copy: the file tier swaps atomically
     * under hot reload, and the check must not observe a half-swapped map.
     */
    @Override
    public Set<String> keys() {
        Set<String> keys = new HashSet<>(fileTier.keySet());
        keys.addAll(sources.cli().keySet());
        keys.addAll(sources.environment().keySet());
        return keys;
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

    /** Re-reads the plan and swaps the file tier. */
    private void reload() {
        apply(filePlan.read());
    }

    /**
     * Applies one already-read snapshot. The snapshot is the only input: a
     * caller that selected profiles from the base files can hand the same
     * parsed maps back, so startup never reads a file twice and hot reload
     * still gets one coherent point-in-time tier.
     */
    private void apply(ConfigFilePlan.Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        // Who declared a key among the override files. A key in two of them is
        // a silent override today — later file wins — which is invisible at
        // the file that lost, so both are named (once per pair, so a hot
        // reload does not repeat it).
        Map<String, Path> declaredBy = new HashMap<>();
        for (ConfigFilePlan.Loaded loaded : snapshot.files()) {
            Path path = loaded.file().path();
            Map<String, String> values = loaded.values();
            // Logging never reads the filesystem override files (it configures
            // itself from -D/env/classpath before this tier exists), so a
            // freeway.log.* key here is silently inert — name it.
            LogKeyNotices.unread(path.toString(), values, warnedUnreadLogKeys);
            Path name = path.getFileName();
            ConfigCascade.warnAboutBootstrapKeys(
                name != null ? name.toString() : path.toString(), values);
            warnAboutDuplicateKeys(path, values, declaredBy);
            values.keySet().forEach(key -> declaredBy.putIfAbsent(key, path));
        }
        fileTier = ConfigMaps.overlay(List.of(sources.files(), snapshot.values()));
    }

    /**
     * Names every key an override file declares that an earlier override file
     * already declared. The classpath baseline is deliberately not compared:
     * an override file shadowing a packaged value is the point of the file
     * tier, while two override files carrying the same key means one of them
     * is dead — and which one depends on a list order that is not visible in
     * either file.
     */
    private void warnAboutDuplicateKeys(
        Path file, Map<String, String> values, Map<String, Path> declaredBy
    ) {
        for (String key : values.keySet()) {
            Path earlier = declaredBy.get(key);
            if (earlier == null || earlier.equals(file)) {
                continue;
            }
            if (warnedDuplicates.add(key + '\u0000' + earlier + '\u0000' + file)) {
                LOG.warn(
                    "Config key {} is declared by both {} and {} — {} wins (later file)",
                    key, earlier, file, file.getFileName());
            }
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
