package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.commons.util.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * <p>Returns an {@link AppConfigDefault} (tiered form): the file tier is watched and
 * re-read on change, so config edits are visible to later symbol lookups
 * without a restart.
 */
public final class ConfigLoaderImpl {
    private static final Logger LOG = LoggerFactory.getLogger(
        ConfigLoaderImpl.class
    );
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** The profile-activation key — see {@link BootConfigLayers#fileBaseline()}. */
    private static final String PROFILE_KEY = "freeway.profile";

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

    public AppConfig load(ClassLoader loader, String... args) {
        // Filesystem base files participate in profile selection alongside
        // the classpath base (filesystem wins, env/CLI win over both).
        BootConfigLayers layers = loadLayers(loader, readFilesystemBase(), args);

        List<Path> overrides = new ArrayList<>();
        Path workDir = Path.of("").toAbsolutePath();
        for (String name : APPLICATION_FILES) {
            overrides.add(workDir.resolve(name));
        }
        for (String profile : layers.profiles()) {
            for (String base : APPLICATION_FILES) {
                overrides.add(workDir.resolve(profileVariant(base, profile)));
            }
        }
        for (String extra : System.getProperty("freeway.config.file", "").split(",")) {
            if (!extra.isBlank()) {
                overrides.add(Path.of(extra.trim()));
            }
        }

        return new AppConfigDefault(
            layers.args(), layers.environment(), layers.fileBaseline(), overrides, layers.profiles());
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

    /** The classpath layers without filesystem base files — the form the
     *  bootstrap log cascade reads ({@link AppLogSource}). */
    static BootConfigLayers loadLayers(ClassLoader loader, String... args) {
        return loadLayers(loader, Map.of(), args);
    }

    /** The layers in ascending priority order. Also consumed by
     *  {@link #load} and {@link AppLogSource}, so the bootstrap
     *  log cascade's application file values match the main cascade's
     *  classpath baseline exactly. */
    static BootConfigLayers loadLayers(
        ClassLoader loader,
        Map<String, String> filesystemBase,
        String... args
    ) {
        Map<String, String> environment = loadEnvironment();
        Map<String, String> properties = loadResource(loader, APPLICATION_PROPERTIES);
        Map<String, String> json = loadResource(loader, APPLICATION_JSON);
        Map<String, String> parsedArgs = parseArgs(args);

        Map<String, String> base = new LinkedHashMap<>();
        // Non-profile layers in ascending priority order (properties → json →
        // filesystem base → environment → args). profile.* layers cannot
        // participate here — their file names ARE the profile selection.
        // Environment must outrank files: FREEWAY_PROFILE driving profile
        // selection would otherwise silently lose to a freeway.profile key
        // in a file.
        base.putAll(properties);
        base.putAll(json);
        base.putAll(filesystemBase);
        base.putAll(environment);
        base.putAll(parsedArgs);

        List<String> profiles = parseProfiles(base.get(PROFILE_KEY));
        Map<String, String> profileProperties = new LinkedHashMap<>();
        Map<String, String> profileJson = new LinkedHashMap<>();
        for (String profile : profiles) {
            profileProperties.putAll(loadResource(loader, profileVariant(APPLICATION_PROPERTIES, profile)));
            profileJson.putAll(loadResource(loader, profileVariant(APPLICATION_JSON, profile)));
        }
        // The activation key is base-layer-only: a profile file that
        // re-declares freeway.profile would otherwise make the merged view
        // contradict profiles(). Stripped once, here — the layers never
        // surface the raw form.
        profileProperties.remove(PROFILE_KEY);
        profileJson.remove(PROFILE_KEY);

        return new BootConfigLayers(
            profiles,
            environment,
            properties,
            json,
            profileProperties,
            profileJson,
            parsedArgs
        );
    }

    private static Map<String, String> loadEnvironment() {
        return loadEnvironment(System.getenv());
    }

    /**
     * Maps environment variables to config keys using the
     * {@code freeway.env.prefix} prefix.
     *
     * <p>The prefix is read exclusively from the JVM system property
     * {@code freeway.env.prefix} (set via {@code -Dfreeway.env.prefix=APP_}).
     * Configuring that key in {@code application.properties}, a profile file,
     * the environment, or CLI arguments does NOT change how the environment
     * layer is mapped — it stays an ordinary config value with no special
     * effect. This is deliberate: the prefix itself would have to come from
     * the cascade's env layer, which the prefix configures — a
     * chicken-and-egg problem — so the JVM property is the only source.</p>
     *
     * <p>Default {@code FREEWAY_} maps into the {@code freeway.*} namespace
     * (backwards compatible); a custom prefix replaces it and passes through
     * verbatim (prefix stripped, {@code _} → {@code .}), so the app owns the
     * whole env-to-config mapping — e.g. prefix {@code APP_} gives
     * {@code APP_SERVER_PORT} → {@code server.port} and
     * {@code APP_FREEWAY_HTTP_PORT} → {@code freeway.http.port}.</p>
     */
    static Map<String, String> loadEnvironment(Map<String, String> environment) {
        String prefix = System.getProperty("freeway.env.prefix", "FREEWAY_").trim();
        if (prefix.isEmpty()) {
            prefix = "FREEWAY_";
        }
        boolean freewayNamespace = "FREEWAY_".equals(prefix);
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
     *  Parsing dispatches by name inside {@link ConfigFileReader#read}. */
    private static Map<String, String> loadResource(ClassLoader loader, String name) {
        try (InputStream bounded = findBoundedStream(loader, name)) {
            if (bounded == null) {
                return Map.of();
            }
            return ConfigFileReader.read(name, bounded);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load " + name, ex);
        }
    }

    /**
     * Opens {@code resourceName} on the given (or default) class loader,
     * wrapping the stream with a 16 MiB read cap. Returns {@code null} when
     * the resource does not exist, so callers can treat it as "no config".
     */
    private static InputStream findBoundedStream(ClassLoader loader, String resourceName) {
        ClassLoader effectiveLoader = loader != null ? loader : ConfigLoaderImpl.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(resourceName);
        if (stream == null) {
            return null;
        }
        return ByteStreams.bounded(stream, 16L * 1024 * 1024, resourceName);
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

    record BootConfigLayers(
        List<String> profiles,
        Map<String, String> environment,
        Map<String, String> properties,
        Map<String, String> json,
        Map<String, String> profileProperties,
        Map<String, String> profileJson,
        Map<String, String> args
    ) {
        public BootConfigLayers {
            profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
            environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
            json = Map.copyOf(Objects.requireNonNull(json, "json"));
            profileProperties = Map.copyOf(Objects.requireNonNull(profileProperties, "profileProperties"));
            profileJson = Map.copyOf(Objects.requireNonNull(profileJson, "profileJson"));
            args = Map.copyOf(Objects.requireNonNull(args, "args"));
        }

        /**
         * The classpath file baseline: base files → profile files (whose
         * activation key was stripped at load time — it is redundant there,
         * since profiles are selected from the base layers only), no
         * environment/CLI. The dynamic file tier overlays the filesystem
         * overrides on top of this.
         */
        public Map<String, String> fileBaseline() {
            Map<String, String> files = new LinkedHashMap<>();
            files.putAll(properties);
            files.putAll(json);
            files.putAll(profileProperties);
            files.putAll(profileJson);
            return Map.copyOf(files);
        }
    }
}
