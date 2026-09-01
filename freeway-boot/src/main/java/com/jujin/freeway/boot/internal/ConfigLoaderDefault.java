package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.AppConfigDefault;
import com.jujin.freeway.boot.ConfigLoader;
import com.jujin.freeway.commons.json.JsonObject;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.util.ByteStreams;
import com.jujin.freeway.commons.util.Maps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ConfigLoader} implementation. Loads configuration from
 * the following sources in ascending priority order:
 * <ol>
 *   <li>{@code application.properties}</li>
 *   <li>{@code application.json}</li>
 *   <li>{@code application-{profile}.properties}</li>
 *   <li>{@code application-{profile}.json}</li>
 *   <li>Environment variables (prefix {@code FREEWAY_}, mapped to dots)</li>
 *   <li>CLI arguments ({@code --key=value})</li>
 * </ol>
 */
public final class ConfigLoaderDefault implements ConfigLoader {
    private static final Logger LOG = LoggerFactory.getLogger(
        ConfigLoaderDefault.class
    );
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** The profile-activation key — see {@link BootConfigLayers#merged()}. */
    private static final String PROFILE_KEY = "freeway.profile";

    /** A value that begins with a minus sign but is a number (e.g. {@code -1}, {@code -2.5}, {@code -1e5}). */
    private static final Pattern NEGATIVE_NUMBER_PATTERN =
        Pattern.compile("-\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    public ConfigLoaderDefault() {
    }

    @Override
    public AppConfig load(ClassLoader loader, String... args) {
        BootConfigLayers layers = loadLayers(loader, args);
        return new AppConfigDefault(layers.merged(), layers.profiles(), layers.configLayers());
    }

    static BootConfigLayers loadLayers(ClassLoader loader, String... args) {
        Map<String, String> environment = loadEnvironment();
        Map<String, String> properties = loadProperties(loader, "application.properties");
        Map<String, String> json = loadJson(loader, "application.json");
        Map<String, String> parsedArgs = parseArgs(args);

        Map<String, String> base = new LinkedHashMap<>();
        // Non-profile layers in merged() priority order (properties → json →
        // environment → args). profile.* layers cannot participate here —
        // their file names ARE the profile selection. Environment must
        // outrank files: FREEWAY_PROFILE driving profile selection would
        // otherwise silently lose to a freeway.profile key in a file.
        base.putAll(properties);
        base.putAll(json);
        base.putAll(environment);
        base.putAll(parsedArgs);

        List<String> profiles = parseProfiles(base.get(PROFILE_KEY));
        Map<String, String> profileProperties = new LinkedHashMap<>();
        Map<String, String> profileJson = new LinkedHashMap<>();
        for (String profile : profiles) {
            profileProperties.putAll(loadProperties(loader, resourceName("application", profile, "properties")));
            profileJson.putAll(loadJson(loader, resourceName("application", profile, "json")));
        }

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

    private static Map<String, String> loadProperties(ClassLoader loader, String resourceName) {
        try (InputStream bounded = findBoundedStream(loader, resourceName)) {
            if (bounded == null) {
                return Map.of();
            }
            Properties properties = new Properties();
            properties.load(bounded);
            Map<String, String> values = new LinkedHashMap<>();
            for (String name : properties.stringPropertyNames()) {
                values.put(name, properties.getProperty(name));
            }
            return values;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load " + resourceName, ex);
        }
    }

    private static Map<String, String> loadJson(ClassLoader loader, String resourceName) {
        try (InputStream bounded = findBoundedStream(loader, resourceName)) {
            if (bounded == null) {
                return Map.of();
            }
            byte[] bytes;
            try {
                bytes = bounded.readAllBytes();
            } catch (IOException ex) {
                throw new IllegalArgumentException("Unable to read JSON input", ex);
            }
            // Decode once and parse the decoded text — the BOM strip below
            // mirrors JsonParser's string path.
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1); // strip UTF-8 BOM like JsonParser
            }
            // An empty or whitespace-only JSON resource means "no config",
            // consistent with an empty application.properties — not a parse
            // error. Malformed non-blank JSON still fails at parseObject.
            if (text.isBlank()) {
                return Map.of();
            }
            JsonObject root = JsonUtils.parseObject(text);
            return Maps.flatten(root.toMap(), ".");
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Unable to load " + resourceName, ex);
        }
    }

    /**
     * Opens {@code resourceName} on the given (or default) class loader,
     * wrapping the stream with a 16 MiB read cap. Returns {@code null} when
     * the resource does not exist, so callers can treat it as "no config".
     */
    private static InputStream findBoundedStream(ClassLoader loader, String resourceName) {
        ClassLoader effectiveLoader = loader != null ? loader : ConfigLoaderDefault.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(resourceName);
        if (stream == null) {
            return null;
        }
        return ByteStreams.bounded(stream, 16L * 1024 * 1024, resourceName);
    }

    private static String resourceName(String baseName, String profile, String suffix) {
        return baseName + "-" + profile + "." + suffix;
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
        /** Symbol-layer names — {@link #configLayers()} maps them to declared
         *  {@code SymbolProvider} orders in {@code BootConfigModule}. */
        static final String NAME_CLI = "cli";
        static final String NAME_ENV = "env";
        static final String NAME_FILES = "files";

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
         * The tiers as symbol-resolution layers, highest priority first:
         * {@code cli} → {@code env} → {@code files}. The files tier merges
         * base and profile files, excluding the profile-activation key from
         * profile layers only — exactly like {@link #merged()}.
         */
        public List<AppConfig.ConfigLayer> configLayers() {
            Map<String, String> files = new LinkedHashMap<>();
            files.putAll(properties);
            files.putAll(json);
            putAllExceptProfileKey(files, profileProperties);
            putAllExceptProfileKey(files, profileJson);
            return List.of(
                new AppConfig.ConfigLayer(NAME_CLI, args),
                new AppConfig.ConfigLayer(NAME_ENV, environment),
                new AppConfig.ConfigLayer(NAME_FILES, files));
        }

        /**
         * Merged view across all layers in ascending priority order:
         * base properties → base json → profile properties → profile json →
         * environment → CLI args.
         *
         * <p>The profile layers contribute configuration but never the
         * profile-activation key: {@code freeway.profile} inside a profile
         * file is redundant — profiles are selected from the base layers
         * only — and would otherwise fork {@code config().get("freeway.profile")}
         * from {@code config().profiles()} (a profile layer outranks the base
         * properties layer). Base-layer {@code freeway.profile}
         * (application.properties/application.json, env, CLI) is preserved —
         * that is the activation mechanism.
         */
        public Map<String, String> merged() {
            Map<String, String> merged = new LinkedHashMap<>();
            merged.putAll(properties);
            merged.putAll(json);
            putAllExceptProfileKey(merged, profileProperties);
            putAllExceptProfileKey(merged, profileJson);
            merged.putAll(environment);
            merged.putAll(args);
            return Map.copyOf(merged);
        }

        /**
         * Copies entries from {@code source} into {@code target}, skipping
         * the profile-activation key (see {@link #merged()}).
         */
        private static void putAllExceptProfileKey(
            Map<String, String> target,
            Map<String, String> source
        ) {
            for (Map.Entry<String, String> entry : source.entrySet()) {
                if (PROFILE_KEY.equals(entry.getKey())) {
                    continue;
                }
                target.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
