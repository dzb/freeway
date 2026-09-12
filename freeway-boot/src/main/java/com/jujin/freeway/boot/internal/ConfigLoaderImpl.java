package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.commons.util.EnvKeys;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standard config cascade. Loads configuration from
 * the following sources in ascending priority order:
 * <ol>
 *   <li>{@code application.properties} / {@code application.json} (classpath
 *       baseline, packaged with the app)</li>
 *   <li>{@code application-{profile}.properties} / {@code application-{profile}.json}
 *       (classpath)</li>
 *   <li>filesystem overrides: the same standard names in the working
 *       directory plus any files listed in {@code freeway.config.file}
 *       (comma-separated) — externalized config, hot-reloaded</li>
 *   <li>Environment variables (prefix {@code FREEWAY_}, mapped to dots)</li>
 *   <li>CLI arguments ({@code --key=value})</li>
 * </ol>
 *
 * <p>Returns an {@link AppConfigDefault} built from the loaded
 * {@link ConfigSources}: the file tier is watched and re-read on change, so
 * config edits are visible to later symbol lookups without a restart.
 */
public final class ConfigLoaderImpl {
    private static final Logger LOG = LoggerFactory.getLogger(
        ConfigLoaderImpl.class
    );
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** The profile-activation key — see {@link #loadLayers}. */
    private static final String PROFILE_KEY = "freeway.profile";

    /** The env-prefix bootstrap key — spelled once, in {@link EnvKeys}. */
    private static final String ENV_PREFIX_KEY = EnvKeys.PREFIX_KEY;

    /** The extra-config-file bootstrap key ({@code -D} or {@code FREEWAY_CONFIG_FILE}). */
    private static final String CONFIG_FILE_KEY = "freeway.config.file";

    /**
     * Keys that configure the cascade itself and therefore cannot come from
     * it: a config file or CLI argument declaring one is ignored and named in
     * a startup WARN (see {@link #warnAboutBootstrapKeys}).
     */
    private static final List<String> BOOTSTRAP_ONLY_KEYS = List.of(
        CONFIG_FILE_KEY, ENV_PREFIX_KEY);

    /** The standard base config file family — one source of truth for the
     *  classpath load, the working-directory overrides and the filesystem
     *  base (profile variants are derived from these names). */
    private static final String APPLICATION_PROPERTIES = "application.properties";
    private static final String APPLICATION_JSON = "application.json";
    private static final List<String> APPLICATION_FILES =
        List.of(APPLICATION_PROPERTIES, APPLICATION_JSON);

    /** A value that begins with a minus sign but is a number (e.g. {@code -1}, {@code -2.5}, {@code -1e5}). */
    private static final Pattern NEGATIVE_NUMBER_PATTERN =
        Pattern.compile("-\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    /**
     * Loads the cascade and returns it: classpath files plus filesystem
     * overrides as the file tier (watched for changes), the mapped
     * environment, and the CLI arguments.
     */
    public static AppConfig load(ClassLoader loader, String... args) {
        // Filesystem base files participate in profile selection alongside
        // the classpath base (filesystem wins, env/CLI win over both).
        ConfigSources sources = loadLayers(loader, readFilesystemBase(), args);

        warnAboutBootstrapKeys("the classpath config files", sources.files());
        warnAboutBootstrapKeys("the command-line arguments", sources.cli());
        return new AppConfigDefault(sources, overrideFiles(sources.profiles()));
    }

    /**
     * The ordered filesystem files the file tier folds over the classpath
     * baseline (later wins): the working-directory base files, then the
     * variants of every active profile, then the files named by the
     * {@code freeway.config.file} bootstrap key.
     *
     * <p>The profile band is laid out exactly as the classpath load lays it
     * out — every profile's {@code .properties}, then every profile's
     * {@code .json} — so the source format outranks the profile order
     * wherever a file lives ({@code application-a.json} beats
     * {@code application-b.properties} on both sides of the baseline).
     *
     * <p>Named separately from {@link #load} because it is the one part of
     * the cascade whose order is pure data: the file names, not their
     * contents, decide precedence.
     */
    static List<Path> overrideFiles(List<String> profiles) {
        Path workDir = Path.of("").toAbsolutePath();
        List<Path> files = new ArrayList<>();
        for (String name : APPLICATION_FILES) {
            files.add(workDir.resolve(name));
        }
        for (String base : APPLICATION_FILES) {
            for (String profile : profiles) {
                files.add(workDir.resolve(profileVariant(base, profile)));
            }
        }
        String configFiles = EnvKeys.bootstrap(CONFIG_FILE_KEY);
        for (String extra : (configFiles == null ? "" : configFiles).split(",")) {
            if (!extra.isBlank()) {
                files.add(Path.of(extra.trim()));
            }
        }
        return List.copyOf(files);
    }

    /**
     * A bootstrap-only key declares where a source comes from, so it must be
     * read before that source exists: a value of such a key in any other
     * channel is ignored. Silently ignoring it is how a misplaced setting
     * survives to production, so name the key and the channel at startup.
     *
     * <p>The classpath file maps arrive merged, so their origin is named by
     * channel; filesystem files are read one by one and name themselves —
     * {@link AppConfigDefault#readOverride} calls this for each of them, which
     * is what covers the working-directory base files, the profile variants
     * and the {@code freeway.config.file} extras alike.
     *
     * @param origin where the value came from — part of the message
     */
    static void warnAboutBootstrapKeys(String origin, Map<String, String> values) {
        for (String key : BOOTSTRAP_ONLY_KEYS) {
            String value = values.get(key);
            if (value != null) {
                LOG.warn(
                    "{} is bootstrap-only (-D{} or {}) — ignoring \"{}\" from {}",
                    key, key, EnvKeys.name(key), value, origin);
            }
        }
    }

    /**
     * Base files in the working directory (filesystem overrides of the
     * packaged {@code application.properties}/{@code application.json}).
     * They participate in profile selection and hot reload — dropping one
     * next to the deployed jar is the standard way to externalize config.
     */
    private static Map<String, String> readFilesystemBase() {
        Map<String, String> base = new LinkedHashMap<>();
        for (String name : APPLICATION_FILES) {
            Path file = Path.of(name).toAbsolutePath();
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                base.putAll(ConfigFileReader.read(file));
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load " + file, e);
            }
        }
        return Map.copyOf(base);
    }

    /** The classpath sources without filesystem base files — the form the
     *  bootstrap log cascade reads ({@link AppLogSource}). */
    static ConfigSources loadLayers(ClassLoader loader, String... args) {
        return loadLayers(loader, Map.of(), args);
    }

    /**
     * Loads every source once and returns them separate, in the tier order the
     * symbol chain consults them. Also consumed by {@link #load} and
     * {@link AppLogSource}, so the bootstrap log cascade's application file
     * values match the main cascade's classpath baseline exactly.
     *
     * @param filesystemBase working-directory base files — they participate in
     *                       profile selection but are not part of the returned
     *                       file tier (the loader passes them separately as
     *                       watched override files)
     */
    static ConfigSources loadLayers(
        ClassLoader loader,
        Map<String, String> filesystemBase,
        String... args
    ) {
        Map<String, String> environment = loadEnvironment(System.getenv());
        Map<String, String> properties = loadResource(loader, APPLICATION_PROPERTIES);
        Map<String, String> json = loadResource(loader, APPLICATION_JSON);
        Map<String, String> cli = parseArgs(args);

        // Non-profile layers in ascending priority order (properties → json →
        // filesystem base → environment → args). profile.* layers cannot
        // participate here — their file names ARE the profile selection.
        // Environment must outrank files: FREEWAY_PROFILE driving profile
        // selection would otherwise silently lose to a freeway.profile key
        // in a file.
        Map<String, String> base = ConfigMaps.overlay(
            List.of(properties, json, filesystemBase, environment, cli));
        List<String> profiles = parseProfiles(base.get(PROFILE_KEY));

        // The profile band merges in two passes: every profile's properties,
        // then every profile's json. The file format therefore outranks the
        // profile order inside the band (application-a.json beats
        // application-b.properties) — the rule is pinned by a test.
        List<Map<String, String>> profileProperties = new ArrayList<>(profiles.size());
        List<Map<String, String>> profileJson = new ArrayList<>(profiles.size());
        for (String profile : profiles) {
            profileProperties.add(withoutActivationKey(
                loadResource(loader, profileVariant(APPLICATION_PROPERTIES, profile))));
            profileJson.add(withoutActivationKey(
                loadResource(loader, profileVariant(APPLICATION_JSON, profile))));
        }
        List<Map<String, String>> fileLayers = new ArrayList<>(2 + profiles.size() * 2);
        fileLayers.add(properties);
        fileLayers.add(json);
        fileLayers.addAll(profileProperties);
        fileLayers.addAll(profileJson);

        return new ConfigSources(
            cli,
            environment,
            ConfigMaps.overlay(fileLayers),
            profiles
        );
    }

    /**
     * The activation key is base-layer-only: a profile file that re-declares
     * {@code freeway.profile} would otherwise make the merged view contradict
     * {@code profiles()}. Stripped here — the raw form never surfaces.
     */
    private static Map<String, String> withoutActivationKey(Map<String, String> values) {
        if (!values.containsKey(PROFILE_KEY)) {
            return values;
        }
        Map<String, String> stripped = new LinkedHashMap<>(values);
        stripped.remove(PROFILE_KEY);
        return stripped;
    }

    /**
     * Maps environment variables to config keys using the
     * {@code freeway.env.prefix} prefix ({@link EnvKeys#prefix()}).
     *
     * <p>The prefix is a bootstrap key: read from {@code -Dfreeway.env.prefix}
     * or {@code FREEWAY_ENV_PREFIX}, never from a config file — a value there
     * is an ordinary config key with no effect on the mapping. This is
     * deliberate: the prefix configures the very env layer it would have to
     * be read from — a chicken-and-egg problem — so only the two bootstrap
     * channels count.</p>
     *
     * <p>The mechanical spelling is the whole rule: the prefix is stripped and
     * {@code _} becomes {@code .}; every other character, a hyphen above all,
     * is carried through verbatim (`key-store` and `key.store` are different
     * keys, so one variable can never feed both).</p>
     *
     * <p>Default {@code FREEWAY_} maps into the {@code freeway.*} namespace
     * (backwards compatible); a custom prefix replaces it and passes through
     * verbatim (prefix stripped, {@code _} → {@code .}), so the app owns the
     * whole env-to-config mapping — e.g. prefix {@code APP_} gives
     * {@code APP_SERVER_PORT} → {@code server.port} and
     * {@code APP_FREEWAY_HTTP_PORT} → {@code freeway.http.port}.</p>
     */
    static Map<String, String> loadEnvironment(Map<String, String> environment) {
        String prefix = EnvKeys.prefix();
        boolean freewayNamespace = EnvKeys.DEFAULT_PREFIX.equals(prefix);
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(prefix)) {
                values.put(convertEnvKey(key, prefix, freewayNamespace), entry.getValue());
            }
        }
        return values;
    }

    /**
     * Converts a prefixed env var name to a config key.
     * The default {@code FREEWAY_} prefix maps into the {@code freeway.*}
     * namespace ({@code "FREEWAY_SERVER_PORT"} → {@code "freeway.server.port"});
     * a custom prefix passes through ({@code "APP_SERVER_PORT"} → {@code "server.port"}).
     */
    static String convertEnvKey(String envKey, String prefix, boolean freewayNamespace) {
        String base = envKey.substring(prefix.length())
            .toLowerCase(Locale.ROOT)
            .replace('_', '.');
        return freewayNamespace ? "freeway." + base : base;
    }

    /** Loads a classpath config resource; a missing resource is "no config".
     *  Parsing and the read cap live inside {@link ConfigFileReader#read}, so
     *  a classpath resource is bounded exactly like a filesystem file. */
    private static Map<String, String> loadResource(ClassLoader loader, String name) {
        ClassLoader effectiveLoader =
            loader != null ? loader : ConfigLoaderImpl.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(name);
        if (stream == null) {
            return Map.of();
        }
        try (stream) {
            return ConfigFileReader.read(name, stream);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load " + name, ex);
        }
    }

    /** The {@code -profile} variant of a base file name:
     *  {@code application.properties} → {@code application-dev.properties}. */
    private static String profileVariant(String base, String profile) {
        int dot = base.lastIndexOf('.');
        return base.substring(0, dot) + '-' + profile + base.substring(dot);
    }

    /**
     * Parses CLI arguments into a key-value map. Supports three styles:
     * <ul>
     *   <li>{@code --key=value}, {@code --key value}, {@code --key} (boolean)</li>
     *   <li>{@code -Dkey=value} (property-style)</li>
     *   <li>{@code -X value} (short flag, two chars including the dash)</li>
     * </ul>
     *
     * <p>Keys without a dot are treated as convenience shortcuts for Freeway
     * framework config and automatically receive the {@code freeway.} prefix.
     * Dotted keys (like {@code server.port} or {@code freeway.profile}) are
     * preserved as-is, allowing application-level config to pass through
     * unchanged.
     */
    static Map<String, String> parseArgs(String... args) {
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) {
                    throw new IllegalArgumentException(
                        "Command-line argument at index " + i
                            + " must not be null");
                }
            }
        }
        List<String> list = args == null ? List.of() : List.of(args);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            String arg = list.get(i);
            if (arg.startsWith("--") || arg.startsWith("-D")) {
                String raw = arg.substring(2);
                int eq = raw.indexOf('=');
                if (eq > 0) {
                    values.put(validateCliKey(raw.substring(0, eq), arg), raw.substring(eq + 1));
                } else {
                    ConsumableValue consumed = consumeValueOrTrue(list, i);
                    values.put(validateCliKey(raw, arg), consumed.value());
                    i = consumed.nextIndex();
                }
            } else if (arg.startsWith("-") && arg.length() == 2) {
                ConsumableValue consumed = consumeValueOrTrue(list, i);
                values.put(validateCliKey(arg.substring(1), arg), consumed.value());
                i = consumed.nextIndex();
            } else {
                LOG.warn(
                    "Ignoring positional command-line argument '{}' — "
                        + "arguments must use --key=value, --key value, or "
                        + "-Dkey=value form",
                    arg
                );
            }
        }
        return values;
    }

    /**
     * The value for a flag at {@code index}: the next argument when it is a
     * consumable value (see {@link #isConsumableValue}), otherwise
     * {@code "true"} (boolean flag). {@code nextIndex} is the index the
     * caller should continue scanning from — it skips the consumed value.
     */
    private static ConsumableValue consumeValueOrTrue(List<String> list, int index) {
        if (index + 1 < list.size() && isConsumableValue(list.get(index + 1))) {
            return new ConsumableValue(list.get(index + 1), index + 1);
        }
        return new ConsumableValue("true", index);
    }

    private record ConsumableValue(String value, int nextIndex) {}

    /**
     * Rejects CLI arguments whose key is empty (bare {@code --} / {@code -D})
     * or contains {@code =} (e.g. {@code --=x}), which would otherwise
     * produce garbage keys like {@code freeway.} or {@code freeway.=x}.
     */
    private static String validateCliKey(String key, String originalArg) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException(
                "Invalid command-line argument '" + originalArg
                    + "': option key must not be empty");
        }
        if (key.indexOf('=') >= 0) {
            throw new IllegalArgumentException(
                "Invalid command-line argument '" + originalArg
                    + "': option key must not contain '=' (use --key=value)");
        }
        return applyFreewayPrefix(key);
    }

    /**
     * A following argument can be consumed as a value when it is not another
     * flag — or when it is a negative number, so {@code --port -1} parses as a
     * value instead of turning {@code --port} into a boolean.
     */
    private static boolean isConsumableValue(String next) {
        return !next.startsWith("-")
            || NEGATIVE_NUMBER_PATTERN.matcher(next).matches();
    }

    /**
     * If {@code key} contains no dot separator it is treated as a convenience
     * shortcut for a Freeway framework property and gets the {@code freeway.}
     * namespace prefix. Dotted keys are returned unchanged.
     */
    private static String applyFreewayPrefix(String key) {
        return key.indexOf('.') < 0 ? "freeway." + key : key;
    }

    private static List<String> parseProfiles(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> profiles = new ArrayList<>();
        for (String part : value.split(",")) {
            String profile = part.trim();
            if (!profile.isEmpty()) {
                if (!validProfileName(profile)) {
                    throw new IllegalArgumentException("Invalid freeway.profile value: " + profile);
                }
                profiles.add(profile);
            }
        }
        return List.copyOf(profiles);
    }

    private static boolean validProfileName(String profile) {
        return PROFILE_NAME_PATTERN.matcher(profile).matches()
            && !profile.contains("..");
    }
}
