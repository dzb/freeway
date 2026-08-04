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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

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
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** A value that begins with a minus sign but is a number (e.g. {@code -1}, {@code -2.5}). */
    private static final Pattern NEGATIVE_NUMBER_PATTERN =
        Pattern.compile("-\\d+(\\.\\d+)?");

    public ConfigLoaderDefault() {
    }

    @Override
    public AppConfig load(ClassLoader loader, String... args) {
        BootConfigLayers layers = loadLayers(loader, args);
        return new AppConfigDefault(layers.merged(), layers.profiles());
    }

    static BootConfigLayers loadLayers(ClassLoader loader, String... args) {
        Map<String, String> environment = loadEnvironment();
        Map<String, String> properties = loadProperties(loader, "application.properties");
        Map<String, String> json = loadJson(loader, "application.json");
        Map<String, String> parsedArgs = parseArgs(args);

        Map<String, String> base = new LinkedHashMap<>();
        base.putAll(environment);
        base.putAll(properties);
        base.putAll(json);
        base.putAll(parsedArgs);

        List<String> profiles = parseProfiles(base.get("freeway.profile"));
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
        String prefix = System.getProperty("freeway.env.prefix", "FREEWAY_");
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(prefix)) {
                String converted = "freeway." + key.substring(prefix.length())
                    .toLowerCase(Locale.ROOT)
                    .replace('_', '.');
                values.put(converted, entry.getValue());
            }
        }
        return values;
    }

    private static Map<String, String> loadProperties(ClassLoader loader, String resourceName) {
        ClassLoader effectiveLoader = loader != null ? loader : ConfigLoaderDefault.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(resourceName);
        if (stream == null) {
            return Map.of();
        }

        try (stream; InputStream bounded = ByteStreams.bounded(stream, 16L * 1024 * 1024, resourceName)) {
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
        ClassLoader effectiveLoader = loader != null ? loader : ConfigLoaderDefault.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(resourceName);
        if (stream == null) {
            return Map.of();
        }

        try (stream; InputStream bounded = ByteStreams.bounded(
                stream, 16L * 1024 * 1024, resourceName)) {
            JsonObject root = JsonUtils.parseObject(bounded);
            return Maps.flatten(root.toMap(), ".");
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Unable to load " + resourceName, ex);
        }
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
        List<String> list = args == null ? List.of() : List.of(args);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            String arg = Objects.requireNonNull(list.get(i), "arg");
            if (arg.startsWith("--")) {
                String raw = arg.substring(2);
                int eq = raw.indexOf('=');
                if (eq > 0) {
                    values.put(applyFreewayPrefix(raw.substring(0, eq)), raw.substring(eq + 1));
                } else if (i + 1 < list.size() && isConsumableValue(list.get(i + 1))) {
                    values.put(applyFreewayPrefix(raw), list.get(++i));
                } else {
                    values.put(applyFreewayPrefix(raw), "true");
                }
            } else if (arg.startsWith("-D")) {
                String raw = arg.substring(2);
                int eq = raw.indexOf('=');
                if (eq > 0) {
                    values.put(applyFreewayPrefix(raw.substring(0, eq)), raw.substring(eq + 1));
                } else if (i + 1 < list.size() && isConsumableValue(list.get(i + 1))) {
                    values.put(applyFreewayPrefix(raw), list.get(++i));
                } else {
                    values.put(applyFreewayPrefix(raw), "true");
                }
            } else if (arg.startsWith("-") && arg.length() == 2) {
                String key = arg.substring(1);
                if (i + 1 < list.size() && isConsumableValue(list.get(i + 1))) {
                    values.put(applyFreewayPrefix(key), list.get(++i));
                } else {
                    values.put(applyFreewayPrefix(key), "true");
                }
            }
        }
        return values;
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

        public Map<String, String> merged() {
            Map<String, String> merged = new LinkedHashMap<>();
            merged.putAll(properties);
            merged.putAll(json);
            merged.putAll(profileProperties);
            merged.putAll(profileJson);
            merged.putAll(environment);
            merged.putAll(args);
            return Map.copyOf(merged);
        }
    }
}
