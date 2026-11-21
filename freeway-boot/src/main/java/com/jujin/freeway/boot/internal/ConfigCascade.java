package com.jujin.freeway.boot.internal;

import com.jujin.freeway.boot.AppConfig;
import com.jujin.freeway.boot.internal.BootModule.ConfigKeys;
import com.jujin.freeway.commons.util.EnvKeys;
import com.jujin.freeway.ioc.symbol.KnownKeys;
import com.jujin.freeway.ioc.symbol.SymbolProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standard config cascade. Loads configuration from
 * the following sources in ascending priority order:
 * <ol>
 *   <li>{@code application.properties} / {@code application.json} /
 *       {@code application.toml} (classpath baseline, packaged with the app)</li>
 *   <li>{@code application-{profile}.properties} / {@code application-{profile}.json} /
 *       {@code application-{profile}.toml} (classpath)</li>
 *   <li>filesystem overrides: the same standard names in the working
 *       directory plus any files listed in {@code freeway.config.file}
 *       (comma-separated) — externalized config, hot-reloaded</li>
 *   <li>Environment variables (prefix {@code FREEWAY_}, mapped to dots)</li>
 *   <li>CLI arguments ({@code --key=value})</li>
 * </ol>
 *
 * <p>JVM system properties ({@code -Dkey=value}) sit between the CLI and the
 * environment in the full chain the container assembles (orders 0/5/10/20:
 * CLI, {@code -D}, env, files). This loader does not read them — the symbol
 * chain does — so a {@code -D} value outranks every file.</p>
 *
 * <p>Named for what it is: a set of pure functions over the cascade layers —
 * no instance, no interface, no binding, nothing to substitute. The {@code
 * Impl} suffix means "container-internal assembly" or "one implementation
 * among siblings", and neither applies here; using it anyway made the suffix
 * mean two different things across the repository.</p>
 *
 * <p>Returns an {@link AppConfigDefault} built from the loaded
 * {@link Sources}: the file tier is watched and re-read on change, so config
 * edits are visible to later symbol lookups without a restart.
 */
public final class ConfigCascade {

    /**
     * The loaded cascade inputs: one flat {@code key=value} map per source,
     * plus the active profiles. The maps stay separate so the symbol chain can
     * answer which tier supplied a value.
     */
    public record Sources(
        Map<String, String> cli,
        Map<String, String> environment,
        Map<String, String> files,
        List<String> profiles
    ) {
        public Sources {
            cli = Map.copyOf(canonicalize("CLI", Objects.requireNonNull(cli, "cli")));
            environment = Map.copyOf(canonicalize(
                "environment variables", Objects.requireNonNull(environment, "environment")));
            files = Map.copyOf(canonicalize(
                "config files", Objects.requireNonNull(files, "files")));
            profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
        }
    }

    private static final Pattern PROFILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /** The profile-activation key — see {@link #loadLayers}. */
    private static final String PROFILE_KEY = ConfigKeys.PROFILE;

    /** The env-prefix bootstrap key. */
    private static final String ENV_PREFIX_KEY = EnvKeys.PREFIX_KEY;

    /** The extra-config-file bootstrap key ({@code -D} or {@code FREEWAY_CONFIG_FILE}). */
    private static final String CONFIG_FILE_KEY = ConfigKeys.CONFIG_FILE;

    /**
     * Keys that configure the cascade itself and therefore cannot come from
     * it: a config file or CLI argument declaring one is ignored and named in
     * a startup WARN (see {@link #warnAboutBootstrapKeys}).
     */
    private static final List<String> BOOTSTRAP_ONLY_KEYS = List.of(
        CONFIG_FILE_KEY, ENV_PREFIX_KEY);

    /** The bootstrap log cascade's view, parsed once per class loader. */
    private static final Map<ClassLoader, Sources> BOOTSTRAP_LAYERS =
        Collections.synchronizedMap(new WeakHashMap<>());

    /** A value that begins with a minus sign but is a number (e.g. {@code -1}, {@code -2.5}, {@code -1e5}). */
    private static final Pattern NEGATIVE_NUMBER_PATTERN =
        Pattern.compile("-\\d+(\\.\\d+)?([eE][+-]?\\d+)?");

    /**
     * Declared <b>after</b> every constant and {@link #BOOTSTRAP_LAYERS}: the
     * first {@code getLogger} can trigger the JUL enhancement, whose
     * {@code AppLogSource} re-enters this class through
     * {@link #bootstrapLayers}; the fields that path reads must already be
     * initialized or the bootstrap log configuration fails with an NPE.
     */
    private static final Logger LOG = LoggerFactory.getLogger(
        ConfigCascade.class
    );

    /**
     * Loads the cascade and returns it: classpath files plus filesystem
     * overrides as the file tier (watched for changes), the mapped
     * environment, and the CLI arguments.
     */
    public static AppConfig load(ClassLoader loader, String... args) {
        Path workDir = Path.of("").toAbsolutePath();
        ConfigFilePlan basePlan = ConfigFilePlan.base(workDir);
        ConfigFilePlan.Snapshot base = basePlan.read();

        // Filesystem base files participate in profile selection alongside
        // the classpath base (filesystem wins, env/CLI win over both). The
        // snapshot is carried into the full read below, so these files are
        // parsed once even though the profile variants are only knowable now.
        Sources sources = loadLayers(loader, base.values(), args);

        warnAboutBootstrapKeys("the classpath config files", sources.files());
        warnAboutBootstrapKeys("the command-line arguments", sources.cli());
        // Logging configures itself before the container exists, so it reads
        // -D, environment variables and the classpath files — not the CLI tier.
        // The class-load-only keys ignore every file, the classpath included.
        LogKeyNotices.unread("the command-line arguments", sources.cli(), null);
        LogKeyNotices.classLoadOnly("the classpath config files", sources.files());
        ConfigFilePlan fullPlan = ConfigFilePlan.full(workDir, sources.profiles());
        return new AppConfigDefault(
            sources, fullPlan, fullPlan.read(base.byPath()));
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

    /** The classpath sources without filesystem base files — the form the
     *  bootstrap log cascade reads ({@link AppLogSource}). */
    static Sources loadLayers(ClassLoader loader, String... args) {
        return loadLayers(loader, Map.of(), args);
    }

    /**
     * The bootstrap log cascade's view: logging configures itself before the
     * container exists, so {@link AppLogSource} reads the classpath files
     * itself; the main cascade later reads the same files again. The classpath
     * is immutable for a deployment, so the parse is cached (weakly keyed by
     * loader) rather than done twice — and the two readers cannot drift on what
     * the files say.
     */
    static Sources bootstrapLayers(ClassLoader loader) {
        return BOOTSTRAP_LAYERS.computeIfAbsent(loader, l -> loadLayers(l, new String[0]));
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
    static Sources loadLayers(
        ClassLoader loader,
        Map<String, String> filesystemBase,
        String... args
    ) {
        Map<String, String> environment = loadEnvironment(System.getenv());
        // The base family in plan order: ConfigFilePlan owns the file names,
        // so a format joins the classpath load without a second hand-written
        // list drifting from the filesystem plan.
        Map<String, Map<String, String>> baseByFormat = new LinkedHashMap<>();
        for (String name : ConfigFilePlan.APPLICATION_FILES) {
            baseByFormat.put(name, loadResource(loader, name));
        }
        Map<String, String> cli = parseArgs(args);

        // Ascending precedence, derived from the symbol chain's own tiers
        // rather than a second hand-ordered list: the chain says a lower
        // TIER_* number wins, and overlay() gives the last entry the win, so
        // the layers are ordered by descending tier. Within one tier the list
        // order is the precedence (stable sort keeps it) — the plan's file
        // family, then the filesystem base. profile.* layers cannot
        // participate here — their file names ARE the profile selection.
        // Environment must outrank files: FREEWAY_PROFILE driving profile
        // selection would otherwise silently lose to a freeway.profile key in
        // a file.
        List<Tier> baseTiers = new ArrayList<>(baseByFormat.size() + 3);
        for (Map<String, String> layer : baseByFormat.values()) {
            baseTiers.add(new Tier(SymbolProvider.TIER_FILES, layer));
        }
        baseTiers.add(new Tier(SymbolProvider.TIER_FILES, filesystemBase));
        baseTiers.add(new Tier(SymbolProvider.TIER_ENV, environment));
        baseTiers.add(new Tier(SymbolProvider.TIER_CLI, cli));
        Map<String, String> base = overlayByTier(baseTiers);
        List<String> profiles = parseProfiles(base.get(PROFILE_KEY));

        // The profile band merges in one pass per format, in plan order:
        // every profile's properties, then every profile's json, then every
        // profile's toml. The file format therefore outranks the profile
        // order inside the band (application-a.toml beats
        // application-b.json) — the rule is pinned by a test.
        List<Map<String, String>> fileLayers =
            new ArrayList<>(baseByFormat.size() * (1 + profiles.size()));
        fileLayers.addAll(baseByFormat.values());
        for (String name : ConfigFilePlan.APPLICATION_FILES) {
            for (String profile : profiles) {
                fileLayers.add(withoutActivationKey(
                    loadResource(loader, ConfigFilePlan.profileVariant(name, profile))));
            }
        }

        return new Sources(
            cli,
            environment,
            overlay(fileLayers),
            profiles
        );
    }

    /**
     * The one place cascade precedence is applied: overlays the layers in
     * list order, with a later layer winning a duplicate key.
     */
    static Map<String, String> overlay(List<Map<String, String>> layers) {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map<String, String> layer : layers) {
            merged.putAll(layer);
        }
        return Map.copyOf(merged);
    }

    /** One cascade layer and the symbol tier it belongs to. */
    private record Tier(int order, Map<String, String> values) {}

    /**
     * Folds layers by the symbol chain's tier order: a lower {@code TIER_*}
     * is higher precedence, and {@link #overlay} applies the later
     * entry last — so the layers are sorted by descending tier.
     */
    private static Map<String, String> overlayByTier(List<Tier> layers) {
        return overlay(
            layers.stream()
                .sorted(java.util.Comparator.comparingInt(Tier::order).reversed())
                .map(Tier::values)
                .toList());
    }

    /**
     * The activation key is base-layer-only: a profile file that re-declares
     * {@code freeway.profile} would otherwise make the merged view contradict
     * {@code profiles()}. Stripped here — the raw form never surfaces.
     */
    static Map<String, String> withoutActivationKey(Map<String, String> values) {
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
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            String key = EnvKeys.fromPrefixedName(prefix, entry.getKey());
            if (key != null) {
                values.put(key, entry.getValue());
            }
        }
        return canonicalize("environment variables", values);
    }

    /** Loads a classpath config resource; a missing resource is "no config".
     *  Parsing and the read cap live inside
     *  {@link ConfigFilePlan.Reader#read}, so a classpath resource is bounded
     *  exactly like a filesystem file. */
    private static Map<String, String> loadResource(ClassLoader loader, String name) {
        ClassLoader effectiveLoader =
            loader != null ? loader : ConfigCascade.class.getClassLoader();
        InputStream stream = effectiveLoader.getResourceAsStream(name);
        if (stream == null) {
            return Map.of();
        }
        try (stream) {
            return ConfigFilePlan.Reader.read(name, stream);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load " + name, ex);
        }
    }

    /**
     * Parses CLI arguments into a key-value map. Supports three styles:
     * <ul>
     *   <li>{@code --key=value}, {@code --key value}, {@code --key} (boolean)</li>
     *   <li>{@code -Dkey=value} (property-style)</li>
     *   <li>{@code -X value} (short flag, two chars including the dash)</li>
     * </ul>
     *
     * <p>Every key is taken literally — no prefix is added, dotted or not.
     * Framework keys are spelled in full ({@code --freeway.profile=dev}), so an
     * application key such as {@code --mode=prod} reaches the application as
     * {@code mode} instead of being rewritten into the framework namespace.
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
        return canonicalize("the command-line arguments", values);
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
     * produce a nameless key nothing can read.
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
        return key;
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
     * Canonicalizes every key from one source and rejects an alias/canonical
     * collision in that same source. Duplicate keys in different tiers remain
     * ordinary precedence overrides and are not rejected here.
     */
    static Map<String, String> canonicalize(String source, Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        Map<String, String> originals = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String original = entry.getKey();
            String canonical = KnownKeys.canonicalize(original);
            String earlier = originals.putIfAbsent(canonical, original);
            if (earlier != null) {
                throw new IllegalArgumentException(
                    "Duplicate config key '" + canonical + "' in " + source
                        + ": '" + earlier + "' and '" + original + "'");
            }
            normalized.put(canonical, entry.getValue());
        }
        return normalized;
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
