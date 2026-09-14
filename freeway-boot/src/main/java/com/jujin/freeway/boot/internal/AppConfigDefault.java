package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;

import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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

    /**
     * A filesystem override file and the role its <em>name</em> plays in the
     * cascade. The role decides two things the reader cannot guess from the
     * path: what to do when the file is not there, and whether the file's
     * {@code freeway.profile} may surface.
     */
    enum Role {
        /** A working-directory base file ({@code application.properties} /
         *  {@code application.json}): optional on disk, and one of the layers
         *  profile selection reads, so its {@code freeway.profile} is honored. */
        BASE,
        /** A profile variant ({@code application-<profile>.*}): optional on
         *  disk, and its {@code freeway.profile} is ignored — the file name
         *  already is the selection. */
        PROFILE_VARIANT,
        /** A file named explicitly by {@code freeway.config.file}: optional on
         *  disk, but its absence is a configuration error worth naming. */
        DECLARED
    }

    /** The ordered override files with their roles — a distinct type so the
     *  path-only convenience constructor below stays unambiguous (two
     *  {@code List} parameters would erase to the same signature). */
    record OverrideFiles(List<OverrideFile> files) {
        OverrideFiles {
            files = List.copyOf(files);
        }

        List<Path> paths() {
            return files.stream().map(OverrideFile::path).toList();
        }
    }

    record OverrideFile(Path path, Role role) {
        static OverrideFile base(Path path) {
            return new OverrideFile(path, Role.BASE);
        }

        static OverrideFile variant(Path path) {
            return new OverrideFile(path, Role.PROFILE_VARIANT);
        }

        static OverrideFile declared(Path path) {
            return new OverrideFile(path, Role.DECLARED);
        }
    }

    private final ConfigSources sources;
    /** Ordered filesystem override files (later wins over earlier). */
    private final List<OverrideFile> overrideFiles;

    /** Current file tier: the classpath baseline overlaid with the overrides. */
    private volatile Map<String, String> fileTier;
    /** Hot-reload watcher; null when there is nothing to watch. */
    private final ConfigFileWatcher watcher;
    /** {@code key\0earlier\0later} of every duplicate already named. */
    private final Set<String> warnedDuplicates = ConcurrentHashMap.newKeySet();

    /**
     * Tiered form: {@code sources} carries the cascade inputs the loader
     * resolved (cli → environment → files, plus the active profiles);
     * {@code sources.files()} overlaid with {@code overrideFiles} forms the
     * file tier, which is watched and re-read on change.
     *
     * <p>Files given as plain paths play the {@link Role#BASE} role — the
     * caller supplies the cascade position, not a file name pattern.</p>
     *
     * @param overrideFiles ordered filesystem files merged over the baseline
     */
    public AppConfigDefault(ConfigSources sources, List<Path> overrideFiles) {
        this(
            sources,
            new OverrideFiles(
                Objects.requireNonNull(overrideFiles, "overrideFiles").stream()
                    .map(OverrideFile::base)
                    .toList()
            )
        );
    }

    AppConfigDefault(ConfigSources sources, OverrideFiles overrideFiles) {
        this.sources = Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(overrideFiles, "overrideFiles");
        this.overrideFiles = overrideFiles.files();
        reload();
        this.watcher = ConfigFileWatcher.start(overrideFiles.paths(), this::reload);
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
        // Who declared a key among the override files. A key in two of them is
        // a silent override today — later file wins — which is invisible at
        // the file that lost, so both are named (once per pair, so a hot
        // reload does not repeat it).
        Map<String, Path> declaredBy = new HashMap<>();
        for (OverrideFile file : overrideFiles) {
            Map<String, String> values = readOverride(file);
            warnAboutDuplicateKeys(file.path(), values, declaredBy);
            values.keySet().forEach(key -> declaredBy.putIfAbsent(key, file.path()));
            layers.add(values); // later files win
        }
        fileTier = ConfigMaps.overlay(layers);
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
    private static Map<String, String> readOverride(OverrideFile file) {
        Path path = file.path();
        if (!Files.isRegularFile(path)) {
            // An absent working-directory file is the normal case. A path the
            // user named explicitly is not: staying silent there is how a typo
            // in freeway.config.file becomes "my overrides do nothing".
            if (file.role() == Role.DECLARED) {
                LOG.warn(
                    "Config file {} is named by freeway.config.file but does not exist"
                        + " — fix the path or drop it from that list",
                    path
                );
            }
            return Map.of();
        }
        Map<String, String> values;
        try {
            values = ConfigFileReader.read(path);
        } catch (IOException e) {
            // A file that exists but cannot be read is a real failure: skipping
            // it drops every key it declares and lets startup continue with
            // values nobody chose. The classpath baseline fails the same way,
            // and on hot reload the watcher keeps the previous snapshot.
            throw new IllegalStateException(
                "Unable to load " + path + " — fix the path or its permissions", e);
        }
        Path name = path.getFileName();
        ConfigLoaderImpl.warnAboutBootstrapKeys(
            name != null ? name.toString() : path.toString(), values);
        // A profile variant's file name already is the selection, so its own
        // freeway.profile must not surface: AppConfig promises that the active
        // list and the resolved value of that key cannot disagree.
        return file.role() == Role.PROFILE_VARIANT
            ? ConfigLoaderImpl.withoutActivationKey(values)
            : values;
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
