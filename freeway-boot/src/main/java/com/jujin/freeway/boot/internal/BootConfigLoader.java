package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.AppConfigDefault;
import com.jujin.freeway.boot.ConfigLoader;
import com.jujin.freeway.commons.json.JsonUtils;
import com.jujin.freeway.commons.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

public final class BootConfigLoader implements ConfigLoader {
    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public BootConfigLoader() {
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
                String converted = key.substring(prefix.length())
                    .toLowerCase()
                    .replace('_', '.');
                values.put(converted, entry.getValue());
            }
        }
        return values;
    }

    private static Map<String, String> loadProperties(ClassLoader loader, String resourceName) {
        ClassLoader effectiveLoader = loader != null ? loader : BootConfigLoader.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(resourceName);
        if (stream == null) {
            return Map.of();
        }

        try (stream; InputStream bounded = bounded(stream, 16L * 1024 * 1024, resourceName)) {
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
        ClassLoader effectiveLoader = loader != null ? loader : BootConfigLoader.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(resourceName);
        if (stream == null) {
            return Map.of();
        }

        try (stream) {
            JsonObject root = JsonUtils.parseObject(stream);
            Map<String, String> values = new LinkedHashMap<>();
            flatten("", root.toMap(), values);
            return values;
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Unable to load " + resourceName, ex);
        }
    }

    private static String resourceName(String baseName, String profile, String suffix) {
        return baseName + "-" + profile + "." + suffix;
    }

    private static InputStream bounded(InputStream stream, long maxBytes, String resourceName) {
        return new InputStream() {
            private long count;

            @Override
            public int read() throws IOException {
                if (count >= maxBytes) {
                    int extra = stream.read();
                    if (extra == -1) {
                        return -1;
                    }
                    throw tooLarge();
                }
                int read = stream.read();
                if (read >= 0) {
                    count++;
                }
                return read;
            }

            @Override
            public int read(byte[] bytes, int off, int len) throws IOException {
                Objects.checkFromIndexSize(off, len, bytes.length);
                if (len == 0) {
                    return 0;
                }
                if (count >= maxBytes) {
                    int extra = stream.read();
                    if (extra == -1) {
                        return -1;
                    }
                    throw tooLarge();
                }
                int allowed = (int) Math.min(len, maxBytes - count);
                int read = stream.read(bytes, off, allowed);
                if (read > 0) {
                    count += read;
                }
                return read;
            }

            private IOException tooLarge() {
                return new IOException(resourceName + " exceeds " + maxBytes + " bytes");
            }
        };
    }

    private static String childKey(String prefix, String key) {
        return prefix.isEmpty() ? key : prefix + "." + key;
    }

    private static void flatten(String prefix, Map<String, Object> source, Map<String, String> target) {
        source.forEach((key, value) -> flattenValue(childKey(prefix, key), value, target));
    }

    private static void flatten(String prefix, List<?> source, Map<String, String> target) {
        for (int i = 0; i < source.size(); i++) {
            flattenValue(prefix + "." + i, source.get(i), target);
        }
    }

    private static void flattenValue(String key, Object value, Map<String, String> target) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = (Map<String, Object>) map;
            flatten(key, nested, target);
        } else if (value instanceof List<?> list) {
            flatten(key, list, target);
        } else if (value != null) {
            target.put(key, String.valueOf(value));
        }
    }

    private static Map<String, String> parseArgs(String... args) {
        List<String> list = args == null ? List.of() : List.of(args);
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            String arg = Objects.requireNonNull(list.get(i), "arg");
            if (arg.startsWith("--")) {
                String raw = arg.substring(2);
                int eq = raw.indexOf('=');
                if (eq > 0) {
                    values.put(raw.substring(0, eq), raw.substring(eq + 1));
                } else if (i + 1 < list.size() && !list.get(i + 1).startsWith("-")) {
                    values.put(raw, list.get(++i));
                } else {
                    values.put(raw, "true");
                }
            } else if (arg.startsWith("-D")) {
                String raw = arg.substring(2);
                int eq = raw.indexOf('=');
                if (eq > 0) {
                    values.put(raw.substring(0, eq), raw.substring(eq + 1));
                }
            } else if (arg.startsWith("-") && arg.length() == 2) {
                String key = arg.substring(1);
                if (i + 1 < list.size() && !list.get(i + 1).startsWith("-")) {
                    values.put(key, list.get(++i));
                }
            }
        }
        return values;
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
