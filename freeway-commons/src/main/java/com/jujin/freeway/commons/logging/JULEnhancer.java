package com.jujin.freeway.commons.logging;
import java.util.concurrent.ConcurrentHashMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Configures JUL with Freeway's console and file logging enhancements.
 * Called at startup regardless of which SLF4J provider is active —
 * these enhancements are pure JDK and do not interfere with SLF4J.
 *
 * <p>Reads {@code freeway-log.properties} from the classpath root as the
 * primary configuration source. System properties ({@code -D}) with the
 * same keys override file values.
 *
 * <h3>Handler ownership contract</h3>
 * Every handler on the root logger falls into exactly one tier, and each
 * tier gets consistent treatment across level, format, and removal rules:
 * <ol>
 *   <li><b>Freeway-owned</b> (created by this class): fully managed —
 *       formatter installed, {@code freeway.log.console.level} applied,
 *       removed by {@code freeway.log.console.enabled=false}.</li>
 *   <li><b>Stock</b> (not freeway-owned, formatter is an unmodified
 *       {@link SimpleFormatter}): treated as JVM defaults — freeway's
 *       console/file formatters are installed over them and they are
 *       removed by {@code enabled=false}, but their level is left alone.</li>
 *   <li><b>Customized</b> (any other formatter): hands off — never
 *       reformatted, re-leveled, or removed.</li>
 * </ol>
 */
final class JULEnhancer {

    private static volatile boolean configured;

    /**
     * Absolute normalized path → the {@link JULFileHandler} Freeway created
     * for that file. Cross-logger dedup: exactly one handler per log file,
     * even when the same path is configured for several loggers (two named
     * files, or a named file aliasing the default file). Two independent
     * handlers on one path would each rotate the shared file: one handler
     * moving the file out from under the other's open stream silently routes
     * that handler's records into the archive (or loses them).
     */
    private static final ConcurrentHashMap<String, JULFileHandler>
        fileHandlersByPath = new ConcurrentHashMap<>();

    private JULEnhancer() {}

    static synchronized void configure() {
        if (configured) return;

        // Force JUL LogManager initialization BEFORE attaching handlers.
        // LogManager is lazily initialized — ensureInitialized() calls
        // readConfiguration() which calls reset(), removing all handlers
        // from all existing loggers. By triggering it here, the reset
        // happens before our handlers are attached and survives. Guarded:
        // a failing LogManager must not abort the whole configuration.
        try {
            LogManager.getLogManager().getLoggerNames();
        } catch (RuntimeException e) {
            logEarly("LogManager initialization failed: " + e);
        }

        try {
            Properties fileConfig = loadFreewayConfig();
            // Clear any stale named file configs from a previous failed
            // configure() run — prevents duplicates on retry.
            namedFileConfigs.clear();
            configureLevels(fileConfig);
            configureConsole(fileConfig);
            installFormatters(fileConfig);
            activateFileLogging(fileConfig);
            configured = true;
        } catch (RuntimeException e) {
            logEarly("SEVERE: Failed to configure JUL logging: " + e);
        }
    }

    /**
     * Emits a diagnostic message to stderr during bootstrap, before JUL
     * logging handlers are fully configured. {@code Logger.warning()} is
     * unreliable here because the user's log environment may suppress
     * console output or handlers may not yet be attached.
     */
    private static void logEarly(String message) {
        System.err.println("[Freeway] " + message);
    }

    // ── config loading ──────────────────────────────────────────

    /**
     * Loads {@code freeway-log.properties} from the classpath root if the
     * user has provided one. Returns an empty {@code Properties} if the
     * file is not present — the framework does not bundle a default copy.
     *
     * <p>Searches the thread context classloader first (user's classpath),
     * then falls back to the classloader that loaded this class
     * (module/JAR boundary), then the system classloader.
     */
    private static Properties loadFreewayConfig() {
        Properties props = new Properties();
        try (InputStream in = openConfigStream()) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            logEarly("Failed to load freeway-log.properties: " + e.getMessage());
        }
        return props;
    }

    /**
     * Opens {@code freeway-log.properties} from the classpath with
     * cascading classloader search:
     * <ol>
     *   <li>Thread context classloader — user application classpath
     *   <li>Own classloader — same JAR/module boundary
     *   <li>System classloader — JVM classpath
     * </ol>
     */
    private static InputStream openConfigStream() {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            InputStream in = tccl.getResourceAsStream("freeway-log.properties");
            if (in != null) return in;
        }
        ClassLoader own = JULEnhancer.class.getClassLoader();
        if (own != null) {
            InputStream in = own.getResourceAsStream("freeway-log.properties");
            if (in != null) return in;
        }
        return ClassLoader.getSystemResourceAsStream("freeway-log.properties");
    }

    /**
     * Maps a config key to its environment variable name, honoring the
     * configurable env prefix ({@code freeway.env.prefix}, default
     * {@code FREEWAY_}) — consistent with {@code ConfigLoaderDefault}'s
     * cascade mapping.
     *
     * <p>Default prefix: {@code "freeway.log.level"} → {@code "FREEWAY_LOG_LEVEL"},
     * {@code "com.myapp.level"} → {@code "COM_MYAPP_LEVEL"}.
     * Custom prefix {@code "APP_"}: {@code "freeway.log.level"} →
     * {@code "APP_FREEWAY_LOG_LEVEL"} (the cascade maps that back to
     * {@code freeway.log.level}).</p>
     */
    static String envKeyFor(String configKey) {
        String prefix = System.getProperty("freeway.env.prefix", "FREEWAY_").trim();
        if (prefix.isEmpty()) {
            prefix = "FREEWAY_";
        }
        String upper = configKey.toUpperCase(Locale.ROOT).replace('.', '_');
        return "FREEWAY_".equals(prefix) ? upper : prefix + upper;
    }

    /**
     * Reads a config value with cascading fallback:
     * <ol>
     *   <li>System property ({@code -Dkey=value}) — highest priority
     *   <li>Environment variable (prefix from {@link #envKeyFor}) — for {@code freeway.*} keys
     *   <li>{@code freeway-log.properties} key
     *   <li>{@code defaultValue}
     * </ol>
     */
    private static String readProperty(
        Properties fileConfig, String key, String defaultValue
    ) {
        // 1. System property
        String sysVal = System.getProperty(key);
        if (sysVal != null) {
            String stripped = sysVal.strip();
            if (!stripped.isEmpty()) return stripped;
        }
        // 2. Environment variable (freeway.log.level → FREEWAY_LOG_LEVEL,
        //    or APP_FREEWAY_LOG_LEVEL under a custom prefix; per-logger keys
        //    like com.myapp.level → COM_MYAPP_LEVEL)
        String envVal = System.getenv(envKeyFor(key));
        if (envVal != null) {
            String stripped = envVal.strip();
            if (!stripped.isEmpty()) return stripped;
        }
        // 3. Config file
        String fileVal = fileConfig.getProperty(key);
        if (fileVal != null) {
            String stripped = fileVal.strip();
            if (!stripped.isEmpty()) return stripped;
        }
        return defaultValue;
    }

    // ── levels ──────────────────────────────────────────────────

    private static void configureLevels(Properties fileConfig) {
        // Root logger level — on failure log and skip, don't abort
        String rootLevel = readProperty(
            fileConfig, "freeway.log.level", "INFO"
        );
        try {
            Logger.getLogger("").setLevel(parseLogLevel(rootLevel));
        } catch (IllegalArgumentException e) {
            logEarly("Invalid root level '" + rootLevel + "': " + e.getMessage());
        }

        // Collect all .level keys from file config, system properties, and
        // environment variables. Framework keys (freeway.log.*) are excluded —
        // they configure the framework itself, not JUL loggers, and treating
        // them as logger levels would create phantom loggers
        // (e.g. "freeway.log.console").
        Set<String> levelKeys = new HashSet<>();
        collectLevelKeys(levelKeys, fileConfig.stringPropertyNames());
        collectLevelKeys(levelKeys, System.getProperties().stringPropertyNames());
        for (String envName : System.getenv().keySet()) {
            String candidate = envToConfigKey(envName);
            if (candidate == null) {
                continue;
            }
            // Only honor env vars whose key is ALSO configured in the file or
            // system properties: an unrelated *_LEVEL variable (LOG_LEVEL,
            // CI_LEVEL, ...) must not create a phantom logger or silently
            // override a logger's level. The env value itself still wins via
            // readProperty's cascade.
            String configKey = resolveConfigKey(candidate, fileConfig);
            if (configKey != null) {
                collectLevelKeys(levelKeys, List.of(configKey));
            }
        }

        for (String key : levelKeys) {
            String effective = readProperty(fileConfig, key, null);
            if (effective == null) continue;

            String loggerName = key.substring(
                0, key.length() - ".level".length()
            );
            try {
                Logger.getLogger(loggerName).setLevel(parseLogLevel(effective));
            } catch (IllegalArgumentException e) {
                logEarly(
                    "Invalid level '" + effective
                        + "' for logger '" + loggerName + "': " + e.getMessage()
                );
            }
        }
    }

    /** Adds per-logger level keys from a key collection, filtering framework keys. */
    private static void collectLevelKeys(Set<String> target, Iterable<String> keys) {
        for (String key : keys) {
            if (
                key.endsWith(".level")
                    && !key.equals("freeway.log.level")
                    && !key.startsWith("freeway.log.")
            ) {
                target.add(key);
            }
        }
    }

    /**
     * Inverse of {@link #envKeyFor}: maps an environment variable name back to
     * its config key ({@code FREEWAY_LOG_LEVEL} → {@code freeway.log.level},
     * or {@code APP_FREEWAY_LOG_LEVEL} under a custom prefix {@code APP_}).
     * Returns {@code null} for environment variables outside the prefix.
     *
     * <p>The mapping folds separators ({@code -}, {@code _} → {@code .}),
     * which cannot be reversed uniquely — a dashed config key such as
     * {@code freeway.log.file.max-size} maps forward to
     * {@code FREEWAY_LOG_FILE_MAX-SIZE} but folds back to
     * {@code freeway.log.file.max.size}. Callers that need the real key must
     * reconcile via {@link #resolveConfigKey}.
     */
    static String envToConfigKey(String envName) {
        String prefix = System.getProperty("freeway.env.prefix", "FREEWAY_").trim();
        if (prefix.isEmpty()) {
            prefix = "FREEWAY_";
        }
        String candidate;
        if ("FREEWAY_".equals(prefix)) {
            candidate = envName;
        } else {
            if (!envName.startsWith(prefix)) {
                return null;
            }
            candidate = envName.substring(prefix.length());
        }
        return candidate.toLowerCase(Locale.ROOT).replace('_', '.');
    }

    /**
     * Reconciles a folded candidate key (see {@link #envToConfigKey}) against
     * the keys actually present in the file config and system properties,
     * returning the real key whose separator-folded form matches — so an env
     * var for a dashed key ({@code FREEWAY_LOG_FILE_MAX_SIZE}) still finds
     * {@code freeway.log.file.max-size}. Returns {@code null} when nothing
     * matches; callers decide which resolved keys they act on.
     */
    static String resolveConfigKey(String candidate, Properties fileConfig) {
        ArrayList<String> known = new ArrayList<>();
        fileConfig.stringPropertyNames().forEach(known::add);
        System.getProperties().stringPropertyNames().forEach(known::add);
        String folded = foldKey(candidate);
        for (String key : known) {
            if (foldKey(key).equals(folded)) {
                return key;
            }
        }
        return null;
    }

    /** Lowercases and folds {@code -}/{@code _} into {@code .}. */
    private static String foldKey(String key) {
        return key.toLowerCase(Locale.ROOT)
            .replace('-', '.')
            .replace('_', '.');
    }

    /**
     * Parses a log level string and returns the corresponding JUL
     * {@link Level}. Accepts both SLF4J convention names and JUL level
     * names — case-insensitive.
     *
     * <table>
     *   <tr><th>SLF4J</th><th>JUL</th></tr>
     *   <tr><td>TRACE</td><td>FINEST / FINER / FINE</td></tr>
     *   <tr><td>DEBUG</td><td>FINE</td></tr>
     *   <tr><td>INFO</td><td>INFO</td></tr>
     *   <tr><td>WARN</td><td>WARNING</td></tr>
     *   <tr><td>ERROR / FATAL</td><td>SEVERE</td></tr>
     *   <tr><td>OFF</td><td>OFF</td></tr>
     *   <tr><td>ALL</td><td>ALL</td></tr>
     * </table>
     */
    static Level parseLogLevel(String value) {
        String upper = value.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "TRACE" -> Level.FINEST;
            case "DEBUG" -> Level.FINE;
            case "INFO"  -> Level.INFO;
            case "WARN", "WARNING" -> Level.WARNING;
            case "ERROR", "SEVERE", "FATAL" -> Level.SEVERE;
            case "OFF"  -> Level.OFF;
            case "ALL"  -> Level.ALL;
            default -> {
                // JUL-specific levels (FINER, FINEST, CONFIG, etc.)
                try {
                    yield Level.parse(upper);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                        "Unknown log level '" + value
                            + "'. Supported: TRACE, DEBUG, INFO, WARN, ERROR, OFF, ALL"
                    );
                }
            }
        };
    }

    // ── console handler ─────────────────────────────────────────

    private static void configureConsole(Properties fileConfig) {
        String enabled = readProperty(
            fileConfig, "freeway.log.console.enabled", "true"
        );

        Logger root = Logger.getLogger("");

        if (!"true".equalsIgnoreCase(enabled)) {
            // Remove freeway-owned and stock ConsoleHandlers; a customized
            // one (non-SimpleFormatter formatter) is the user's deliberate
            // configuration and stays — see the ownership contract.
            for (Handler h : root.getHandlers()) {
                if (h instanceof ConsoleHandler && manageable(h)) {
                    root.removeHandler(h);
                    h.close();
                }
            }
            return;
        }

        // Ensure at least one ConsoleHandler exists
        boolean hasConsole = false;
        String level = readProperty(
            fileConfig, "freeway.log.console.level", null
        );
        for (Handler h : root.getHandlers()) {
            if (h instanceof ConsoleHandler) {
                hasConsole = true;
                // Only adjust levels of ConsoleHandlers Freeway itself
                // created. A user-configured ConsoleHandler (via
                // logging.properties or code) keeps its own level — the same
                // hands-off contract installFormatters() applies to
                // formatters, so freeway.log.console.level must not override
                // a level the user set deliberately.
                if (freewayHandlers.contains(h) && level != null) {
                    try {
                        h.setLevel(parseLogLevel(level));
                    } catch (IllegalArgumentException e) {
                        logEarly(
                            "Invalid console level '" + level
                                + "': " + e.getMessage()
                        );
                    }
                }
            }
        }

        if (!hasConsole) {
            ConsoleHandler ch = new ConsoleHandler();
            freewayHandlers.add(ch);
            String effective = level != null ? level : "INFO";
            try {
                ch.setLevel(parseLogLevel(effective));
            } catch (IllegalArgumentException e) {
                logEarly("Invalid console level '" + effective + "': " + e.getMessage());
                ch.setLevel(Level.INFO); // safe fallback
            }
            root.addHandler(ch);
        }
    }

    // ── formatter installation ──────────────────────────────────

    private static void installFormatters(Properties fileConfig) {
        if ("simple".equalsIgnoreCase(formatMode(fileConfig))) {
            // Opt out: leave JUL's native SimpleFormatter in place.
            return;
        }
        // Auto (default): install Freeway's JUL formatters on handlers the
        // framework created itself — never on user-configured handlers.
        JULConsoleFormatter consoleFmt = new JULConsoleFormatter();
        JULFileFormatter fileFmt = new JULFileFormatter();
        for (Handler h : freewayHandlers) {
            applyFormatter(h, fileFmt, consoleFmt);
        }
        // Also upgrade JUL's stock root handlers (e.g. the JVM default console
        // handler): their formatter is the unmodified SimpleFormatter, so the
        // user has not customized it. Handlers carrying a non-default
        // formatter are left untouched.
        for (Handler h : Logger.getLogger("").getHandlers()) {
            if (freewayHandlers.contains(h)) {
                continue;
            }
            if (!manageable(h)) {
                continue;
            }
            applyFormatter(h, fileFmt, consoleFmt);
        }
    }

    /**
     * Installs Freeway's file formatter on file handlers and the console
     * formatter on everything else — the same dispatch used for both
     * framework-created handlers and JUL's stock root handlers.
     */
    private static void applyFormatter(
        Handler h,
        Formatter fileFmt,
        Formatter consoleFmt
    ) {
        if (h instanceof FileHandler || h instanceof JULFileHandler) {
            h.setFormatter(fileFmt);
        } else {
            h.setFormatter(consoleFmt);
        }
    }

    /**
     * Whether Freeway may reformat or remove a handler it did not create:
     * only stock ones (unmodified {@link SimpleFormatter}, i.e. JVM defaults)
     * — anything customized is the user's deliberate configuration.
     */
    private static boolean manageable(Handler h) {
        Formatter fmt = h.getFormatter();
        return fmt == null || fmt instanceof SimpleFormatter;
    }

    /**
     * Resolves the {@code freeway.log.format} switch.
     * Unset or blank defaults to {@code auto}; unknown values warn and
     * also fall back to {@code auto}.
     *
     * <p>Supports system property ({@code -Dfreeway.log.format}), env var
     * ({@code FREEWAY_LOG_FORMAT}), and {@code freeway-log.properties}.
     */
    private static String formatMode(Properties fileConfig) {
        String v = readProperty(fileConfig, "freeway.log.format", "auto");
        if ("auto".equalsIgnoreCase(v) || "simple".equalsIgnoreCase(v)) {
            return v.toLowerCase(Locale.ROOT);
        }
        logEarly(
            "Unknown freeway.log.format '" + v + "' — using 'auto'"
        );
        return "auto";
    }

    // ── file logging activation ─────────────────────────────────

    static synchronized void resetForTest() {
        configured = false;
        namedFilesApplied = false;
        namedFileConfigs.clear();
        fileHandlersByPath.clear();
        freewayHandlers.clear();
        LogManager logManager = LogManager.getLogManager();
        var names = logManager.getLoggerNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            Logger logger = logManager.getLogger(name);
            if (logger == null) continue;
            for (Handler handler : logger.getHandlers()) {
                if (
                    handler instanceof JULFileHandler ||
                    handler instanceof FileHandler
                ) {
                    logger.removeHandler(handler);
                    try {
                        handler.close();
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    // ── named file config persistence ───────────────────────────

    private static final List<NamedFileConfig> namedFileConfigs = new ArrayList<>();
    private static boolean namedFilesApplied;

    /**
     * Handlers created by Freeway itself. Formatter installation only touches
     * these — a user-configured JUL handler (via logging.properties or code)
     * keeps its own formatter instead of being silently replaced.
     */
    private static final Set<Handler> freewayHandlers =
        ConcurrentHashMap.newKeySet();

    /**
     * Returns the registered handler for {@code path}, or {@code null} when
     * none is usable. A handler closed by {@link LogManager#reset()} (e.g. a
     * late LogManager initialization sequence) is removed from the registry
     * so the caller creates a fresh one — re-attaching a closed handler
     * would silently drop every record.
     */
    private static JULFileHandler registeredHandler(Path path) {
        JULFileHandler existing = fileHandlersByPath.get(path.toString());
        if (existing != null && existing.isClosed()) {
            fileHandlersByPath.remove(path.toString(), existing);
            return null;
        }
        return existing;
    }

    private static void registerHandler(Path path, JULFileHandler handler) {
        fileHandlersByPath.putIfAbsent(path.toString(), handler);
    }

    /** Debug-level note; only visible when JUL FINE diagnostics are enabled. */
    private static void logDedup(String message) {
        java.util.logging.Logger.getLogger(
            JULEnhancer.class.getName()
        ).fine(message);
    }

    /**
     * Re-applies all named file handler configurations. Safe to call any
     * number of times — {@link #attachNamedFile(NamedFileConfig)} skips
     * files whose handler is already attached to the target logger, so no
     * duplicate handlers are ever created.
     *
     * <p>Intended for late-stage re-attachment when handlers configured
     * during {@link #configure()} may have been cleared by
     * {@link LogManager} initialization sequences. Application code
     * (or a {@code RuntimeHook}) calls this after the runtime is fully
     * started to ensure named loggers have their file handlers present.
     */
    static synchronized void applyNamedFileConfigs() {
        if (namedFilesApplied) return;
        namedFilesApplied = true;
        for (NamedFileConfig cfg : namedFileConfigs) {
            attachNamedFile(cfg);
        }
    }

    private record NamedFileConfig(
        String path,
        long maxSize,
        int maxHistory,
        boolean compress,
        long flushIntervalMs,
        Level level,
        String loggerName
    ) {}

    private static void attachNamedFile(NamedFileConfig cfg) {
        try {
            Logger target = (cfg.loggerName != null)
                ? Logger.getLogger(cfg.loggerName)
                : Logger.getLogger(""); // root
            Path configuredPath = Paths.get(cfg.path).toAbsolutePath().normalize();

            // Local dedup: this logger already carries a handler for the
            // file. The same named file is attached once at configure()
            // time and re-attached by applyNamedFileConfigs() after the
            // runtime is up; without this check the second pass would add a
            // second handler writing to the same file — doubling records and
            // giving the two handlers independent rotation states on one
            // file (records lost into archives). Skip when already present.
            for (Handler h : target.getHandlers()) {
                if (h instanceof JULFileHandler fh
                        && fh.basePath().normalize().equals(configuredPath)) {
                    return;
                }
            }

            // Global dedup: another logger may already own this file (two
            // named files, or a named file aliasing the default file). One
            // file must have exactly one handler; reuse the registered
            // handler for the second logger instead of creating a duplicate
            // with its own rotation state.
            JULFileHandler handler = registeredHandler(configuredPath);
            if (handler == null) {
                handler = new JULFileHandler(
                    cfg.path,
                    cfg.maxSize,
                    cfg.maxHistory,
                    cfg.compress,
                    cfg.flushIntervalMs
                );
                registerHandler(configuredPath, handler);
            } else {
                logDedup(
                    "Reusing existing handler for log file '" + cfg.path
                        + "' (same file already owned by another logger)"
                );
            }
            freewayHandlers.add(handler);
            if (cfg.level != null) handler.setLevel(cfg.level);

            target.addHandler(handler);
            if (target.getParent() != null) {
                target.setUseParentHandlers(false);
            }
        } catch (IOException | RuntimeException e) {
            logEarly(
                "Failed to attach named log file '"
                    + cfg.path + "': " + e.getMessage()
            );
        }
    }

    private static String resolveDefaultPath() {
        String appName = System.getProperty("app.name");
        if (appName == null || appName.isBlank()) appName = "freeway";
        return "logs/" + appName + ".log";
    }

    /**
     * Activates file logging. Always creates the default single file from
     * {@code freeway.log.file} (existing behavior). When
     * {@code freeway.log.files} is also set, creates additional named
     * log files — one per entry in the comma-separated list.
     *
     * <p>Each named file is configured via:
     * <pre>{@code
     * freeway.log.file.<name>.path=logs/name.log   (required)
     * freeway.log.file.<name>.logger=com.example   (optional; root if absent)
     * freeway.log.file.<name>.level=FINE           (optional; inherits parent)
     * freeway.log.file.<name>.max-size=104857600   (optional; default 100 MB)
     * freeway.log.file.<name>.max-history=30       (optional; default 30 days)
     * freeway.log.file.<name>.compress=true        (optional; default true)
     * }</pre>
     */
    private static void activateFileLogging(Properties fileConfig) {
        String raw = readProperty(fileConfig, "freeway.log.file", "auto");
        Function<String, String> reader = cascadeReader(fileConfig);

        if (!"off".equalsIgnoreCase(raw)) {
            String path;
            if ("auto".equalsIgnoreCase(raw)) {
                path = resolveDefaultPath();
            } else {
                path = raw;
            }

            try {
                Path configuredPath = Paths.get(path).toAbsolutePath().normalize();
                JULFileHandler fh = registeredHandler(configuredPath);
                if (fh == null) {
                    FileSettings settings = fileSettings(
                        "freeway.log.file",
                        reader
                    );
                    fh = new JULFileHandler(
                        path,
                        settings.maxSize(),
                        settings.maxHistory(),
                        settings.compress(),
                        settings.flushIntervalMs()
                    );
                    registerHandler(configuredPath, fh);
                } else {
                    logDedup(
                        "Reusing existing handler for default log file '"
                            + path + "' (same file already owned by another logger)"
                    );
                }
                freewayHandlers.add(fh);
                Logger.getLogger("").addHandler(fh);
            } catch (IOException | RuntimeException e) {
                logEarly(
                    "Failed to activate file logging for '"
                        + path + "': " + e.getMessage()
                );
            }
        }

        // ── additional named files ──────────────────────────────
        String files = readProperty(fileConfig, "freeway.log.files", null);
        if (files == null) return;
        for (String name : files.split(",")) {
            name = name.strip();
            if (!name.isEmpty()) activateNamedFile(fileConfig, name);
        }
    }

    /**
     * Creates a {@link JULFileHandler} from
     * {@code freeway.log.file.<name>.*} properties and attaches it to
     * the target logger.
     */
    private static void activateNamedFile(Properties fileConfig, String name) {
        String prefix = "freeway.log.file." + name;
        String path = readProperty(fileConfig, prefix + ".path", null);

        if (path == null) {
            logEarly(
                "Skipping log file '" + name + "': "
                    + prefix + ".path is not set"
            );
            return;
        }

        Level level = null;
        String levelStr = readProperty(fileConfig, prefix + ".level", null);
        if (levelStr != null) {
            try {
                level = parseLogLevel(levelStr);
            } catch (IllegalArgumentException e) {
                logEarly("Invalid level '" + levelStr + "' for log file '" + name + "': " + e.getMessage());
            }
        }

        String loggerName = readProperty(fileConfig, prefix + ".logger", null);
        Function<String, String> reader = cascadeReader(fileConfig);
        FileSettings settings = fileSettings(prefix, reader);
        NamedFileConfig cfg = new NamedFileConfig(
            path,
            settings.maxSize(),
            settings.maxHistory(),
            settings.compress(),
            settings.flushIntervalMs(),
            level,
            loggerName
        );
        namedFileConfigs.add(cfg);
        attachNamedFile(cfg);
    }

    // ── property helpers ────────────────────────────────────────

    /** Rotation/compression settings parsed for one log file key prefix. */
    private record FileSettings(
        long maxSize,
        int maxHistory,
        boolean compress,
        long flushIntervalMs
    ) {}

    /**
     * Reads the four rotation/compression settings shared by the default
     * file and each named file ({@code max-size}, {@code max-history},
     * {@code compress}, {@code flush-interval}) under {@code prefix},
     * each falling back to its built-in default.
     */
    private static FileSettings fileSettings(
        String prefix,
        Function<String, String> reader
    ) {
        return new FileSettings(
            LogConfig.propertyValue(
                prefix + ".max-size",
                JULFileHandler.DEFAULT_MAX_SIZE,
                reader,
                Long::parseLong,
                true
            ),
            LogConfig.propertyValue(
                prefix + ".max-history",
                JULFileHandler.DEFAULT_MAX_HISTORY,
                reader,
                Integer::parseInt,
                true
            ),
            LogConfig.propertyValue(
                prefix + ".compress",
                JULFileHandler.DEFAULT_COMPRESS,
                reader,
                LogConfig::strictBoolean,
                true
            ),
            LogConfig.propertyValue(
                prefix + ".flush-interval",
                JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS,
                reader,
                Long::parseLong,
                true
            )
        );
    }

    /**
     * The cascade reader used for every {@code freeway.log.*} lookup —
     * {@code -D} > env > file > default (see {@link #readProperty}).
     * Parse helpers live in {@link LogConfig}; the cascade parses leniently
     * (an unparseable value falls back to its default instead of failing the
     * whole configuration).
     */
    private static Function<String, String> cascadeReader(Properties fileConfig) {
        return k -> readProperty(fileConfig, k, null);
    }
}
