package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.internal.BootModule.ConfigKeys;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.toml.TomlUtils;
import com.jujin.freeway.commons.util.ByteStreams;
import com.jujin.freeway.commons.util.EnvKeys;
import com.jujin.freeway.commons.util.Maps;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The filesystem side of the config cascade: the standard file family, each
 * file's role, its precedence position, and one read that produces a snapshot
 * for both profile selection and the live file tier.
 *
 * <p>The plan is deliberately read-only metadata; {@link #read} is the one
 * snapshot entry point and nested {@link Reader} is the one place a file is
 * parsed. A caller that already has a snapshot can pass its per-path maps back
 * in, so the working-directory base files are not read a second time merely
 * because the active profiles became known after the first read.</p>
 */
final class ConfigFilePlan {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigFilePlan.class);

    /** Standard base file family — one source of truth for load, override and watch. */
    static final String APPLICATION_PROPERTIES = "application.properties";
    static final String APPLICATION_JSON = "application.json";
    static final String APPLICATION_TOML = "application.toml";
    static final List<String> APPLICATION_FILES =
        List.of(APPLICATION_PROPERTIES, APPLICATION_JSON, APPLICATION_TOML);

    /** What role a file's name plays in the cascade. */
    enum Role {
        /** Working-directory base file; its profile key participates in selection. */
        BASE,
        /** Profile variant; its own profile key is stripped because the name selects it. */
        PROFILE_VARIANT,
        /** File named explicitly by freeway.config.file; absence is worth naming. */
        DECLARED
    }

    /** One ordered file and the role its name gives it. */
    record File(Path path, Role role) {
        File {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(role, "role");
        }
    }

    private final List<File> files;

    ConfigFilePlan(List<File> files) {
        this.files = List.copyOf(Objects.requireNonNull(files, "files"));
    }

    /** The working-directory base files only — enough to select profiles. */
    static ConfigFilePlan base(Path workDir) {
        List<File> files = new ArrayList<>(APPLICATION_FILES.size());
        for (String name : APPLICATION_FILES) {
            files.add(new File(workDir.resolve(name), Role.BASE));
        }
        return new ConfigFilePlan(files);
    }

    /** Explicit plain paths, all treated as base overrides (the long-standing constructor contract). */
    static ConfigFilePlan paths(List<Path> paths) {
        Objects.requireNonNull(paths, "paths");
        List<File> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            files.add(new File(Objects.requireNonNull(path, "path"), Role.BASE));
        }
        return new ConfigFilePlan(files);
    }

    /**
     * The complete filesystem tier: base files, profile variants and files
     * named by {@code freeway.config.file}, in precedence order (later wins).
     * Profile variant order is properties-per-profile, then json-per-profile,
     * then toml-per-profile, matching the classpath load.
     */
    static ConfigFilePlan full(Path workDir, List<String> profiles) {
        Objects.requireNonNull(profiles, "profiles");
        List<File> files = new ArrayList<>();
        for (String name : APPLICATION_FILES) {
            files.add(new File(workDir.resolve(name), Role.BASE));
        }
        for (String base : APPLICATION_FILES) {
            for (String profile : profiles) {
                files.add(new File(
                    workDir.resolve(profileVariant(base, profile)), Role.PROFILE_VARIANT));
            }
        }
        String configFiles = EnvKeys.bootstrap(ConfigKeys.CONFIG_FILE);
        for (String extra : (configFiles == null ? "" : configFiles).split(",")) {
            if (!extra.isBlank()) {
                files.add(new File(Path.of(extra.trim()), Role.DECLARED));
            }
        }
        return new ConfigFilePlan(files);
    }

    List<File> files() {
        return files;
    }

    List<Path> paths() {
        return files.stream().map(File::path).toList();
    }

    /** Reads this plan without a preloaded snapshot. */
    Snapshot read() {
        return read(Map.of());
    }

    /**
     * Reads this plan, reusing any already-parsed file whose exact path is in
     * {@code preloaded}. The preloaded maps are treated as already role-processed
     * (the profile-selection read only preloads base files).
     */
    Snapshot read(Map<Path, Map<String, String>> preloaded) {
        Objects.requireNonNull(preloaded, "preloaded");
        List<Loaded> loaded = new ArrayList<>(files.size());
        for (File file : files) {
            Map<String, String> values = preloaded.get(file.path());
            if (values == null) {
                values = load(file);
            }
            loaded.add(new Loaded(file, values));
        }
        return new Snapshot(loaded);
    }

    private static Map<String, String> load(File file) {
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
            values = Reader.read(path);
        } catch (IOException e) {
            throw new IllegalStateException(
                "Unable to load " + path + " — fix the path or its permissions", e);
        }
        // A profile variant's file name already is the selection, so its own
        // freeway.profile must not surface: AppConfig promises that the active
        // list and the resolved value of that key cannot disagree.
        return file.role() == Role.PROFILE_VARIANT
            ? ConfigCascade.withoutActivationKey(values)
            : values;
    }

    /** One file's parsed values, with its precedence role retained. */
    record Loaded(File file, Map<String, String> values) {
        Loaded {
            values = Map.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    /** A point-in-time filesystem tier and its per-file provenance. */
    record Snapshot(List<Loaded> files) {
        Snapshot {
            files = List.copyOf(Objects.requireNonNull(files, "files"));
        }

        /** The override layers overlaid in plan order (later wins). */
        Map<String, String> values() {
            return ConfigCascade.overlay(files.stream().map(Loaded::values).toList());
        }

        /** Parsed values keyed by exact plan path, for a later read to reuse. */
        Map<Path, Map<String, String>> byPath() {
            Map<Path, Map<String, String>> byPath = new LinkedHashMap<>();
            for (Loaded loaded : files) {
                byPath.put(loaded.file().path(), loaded.values());
            }
            return Map.copyOf(byPath);
        }
    }

    /** {@code application.properties} + profile → {@code application-dev.properties}. */
    static String profileVariant(String base, String profile) {
        int dot = base.lastIndexOf('.');
        return dot < 0
            ? base + "-" + profile
            : base.substring(0, dot) + "-" + profile + base.substring(dot);
    }

    /**
     * The one config-file parser. A source is read as JSON when its name ends
     * with {@code .json}, as TOML when it ends with {@code .toml} (both
     * case-insensitive) and as {@code java.util.Properties} otherwise; all
     * three formats are read as UTF-8. The classpath cascade, the
     * working-directory base files, the {@code freeway.config.file} extras and
     * the hot-reload re-read all go through this nested reader, so a file
     * parses identically no matter where it lives or when it is read.
     *
     * <p>Only the <em>explicitly named</em> file's extension is read this way.
     * The standard family names are lowercase and resolved exactly, so
     * {@code application.TOML} beside the lowercase files is a different
     * (never loaded) path, not an alternate spelling.
     *
     * <p>Both read entries apply the same read cap, so a runaway file fails
     * identically whether it sits on the classpath or in the working directory.
     */
    static final class Reader {

        /**
         * Read cap for every config source: a file that exceeds it fails
         * loudly instead of exhausting memory.
         */
        static final long MAX_BYTES = 16L * 1024 * 1024;

        private Reader() {}

        /** Reads a filesystem config file, dispatching by extension. */
        static Map<String, String> read(Path file) throws IOException {
            String name = file.getFileName() != null ? file.getFileName().toString() : "";
            try (InputStream in = Files.newInputStream(file)) {
                return read(name, in);
            }
        }

        /** Reads a config source, dispatching by name extension. */
        static Map<String, String> read(String name, InputStream in) throws IOException {
            try (InputStream bounded = ByteStreams.bounded(in, MAX_BYTES, name)) {
                return parse(name, bounded);
            }
        }

        /** Dispatches on the name extension; see the class javadoc. */
        private static Map<String, String> parse(String name, InputStream in) throws IOException {
            String lowered = name.toLowerCase(Locale.ROOT);
            if (lowered.endsWith(".json")) {
                return ConfigCascade.canonicalize(
                    name, json(new String(in.readAllBytes(), StandardCharsets.UTF_8), name));
            }
            if (lowered.endsWith(".toml")) {
                return ConfigCascade.canonicalize(
                    name, toml(new String(in.readAllBytes(), StandardCharsets.UTF_8), name));
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            Map<String, String> values = new LinkedHashMap<>();
            props.forEach((k, v) -> values.put(String.valueOf(k), String.valueOf(v)));
            return ConfigCascade.canonicalize(name, values);
        }

        /**
         * Parses JSON config text: a UTF-8 BOM is tolerated, a blank document
         * contributes nothing, nested objects flatten to dotted keys. Malformed
         * JSON fails with the source named in the message.
         */
        private static Map<String, String> json(String text, String name) {
            if (text.startsWith("\uFEFF")) {
                // The JSON parser strips a leading BOM itself, so this is not
                // needed to parse. It is what makes a document consisting only
                // of a BOM count as blank below.
                text = text.substring(1);
            }
            if (text.isBlank()) {
                return Map.of();
            }
            try {
                return Maps.flatten(JsonUtils.parseObject(text).toMap(), ".");
            } catch (RuntimeException e) {
                throw new IllegalStateException("Unable to load " + name, e);
            }
        }

        /**
         * Parses TOML config text with the same shape as {@link #json}: the
         * commons {@code toml} subset parses the tree (tolerating a UTF-8 BOM
         * and yielding an empty table for a blank document), and
         * {@link Maps#flatten} renders it as dotted keys. Malformed TOML fails
         * with the source named in the message; the cause carries the line and
         * column ({@code TomlParseException}).
         */
        private static Map<String, String> toml(String text, String name) {
            try {
                return Maps.flatten(TomlUtils.parse(text), ".");
            } catch (RuntimeException e) {
                throw new IllegalStateException("Unable to load " + name, e);
            }
        }
    }
}
