package com.jujin.freeway.commons.logging;

import com.jujin.freeway.commons.util.EnvKeys;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * <p>Log keys can live in two file homes: {@code freeway-logging.properties} on
 * the classpath root (the dedicated, more specific file) or the app's main
 * config files — the boot cascade's classpath file baseline, supplied
 * through the {@link LogConfigSource} contract and taking only
 * {@code freeway.log.*} keys. System properties ({@code -D}) and
 * environment variables override both file homes.
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
            Properties fileConfig = loadLogConfig();
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

    private static final String LOG_PROPERTIES = "freeway-logging.properties";

    /**
     * The pre-1.5.2 name of {@link #LOG_PROPERTIES}. Never read: a file under
     * this name is only <em>detected</em>, so a classpath still carrying it
     * gets a startup notice naming the rename instead of losing its logging
     * configuration in silence. Detection is not a fallback — the renamed file
     * is the only name this class loads.
     */
    private static final String RENAMED_LOG_PROPERTIES = "freeway-log.properties";

    /**
     * Loads the log configuration, merging the app-side source below the
     * dedicated file:
     * <ol>
     *   <li>{@link LogConfigSource#values()} — the application's main config
     *       files, supplied by the boot layer via ServiceLoader — lowest
     *       precedence, filling only what nothing above set
     *   <li>{@code freeway-logging.properties} from the classpath root — the
     *       dedicated log file, the more specific declaration
     * </ol>
     * The application-side source is a boot-supplied contract
     * ({@link LogConfigSource}) — commons consumes it, boot owns the file
     * family. No provider (a bare container without boot) degenerates to the
     * dedicated file plus -D/env. The container's
     * config cascade is not involved: this runs at bootstrap, before any
     * container exists.
     */
    static Properties loadLogConfig() {
        return loadLogConfig(resolvedSource());
    }

    static Properties loadLogConfig(LogConfigSource source) {
        Properties merged = new Properties();
        if (source != null) {
            // Defensive filter: the contract says only freeway.log.* keys
            // arrive, but the consumer enforces it — a buggy provider must
            // not feed the per-logger level enumeration phantom loggers.
            mergeLogKeys(merged, source.values());
        }
        InputStream in = openStream(LOG_PROPERTIES);
        if (in != null) {
            try (InputStream stream = in) {
                merged.load(stream);
            } catch (IOException e) {
                logEarly("Failed to load " + LOG_PROPERTIES + ": " + e.getMessage());
            }
        }
        // Not a fallback: the renamed file is never loaded, so the two names
        // cannot both be live. A classpath still carrying it is told so — with
        // or without the canonical file present, because a dead config file
        // that looks live is a trap for whoever edits it next.
        String notice = renamedFileNotice();
        if (notice != null) {
            logEarly(notice);
        }
        return merged;
    }

    /**
     * The rename notice for a classpath that still carries the old file name,
     * or {@code null} when there is nothing to report. Package-visible for
     * tests: the message is the whole migration path for that file, so it is
     * pinned by a test rather than left to a log line.
     */
    static String renamedFileNotice() {
        if (openStream(RENAMED_LOG_PROPERTIES) == null) {
            return null;
        }
        return RENAMED_LOG_PROPERTIES + " is on the classpath but is no longer read:"
            + " the logging configuration file was renamed to " + LOG_PROPERTIES
            + " — rename the file, otherwise the keys in it do not apply";
    }

    private static void mergeLogKeys(Properties merged, Map<String, String> values) {
        values.forEach((key, value) -> {
            if (key.startsWith(LogKeys.PREFIX)) {
                merged.setProperty(key, value);
            }
        });
    }

    /** Resolves the boot-supplied homes once; absent without the boot layer. */
    private static LogConfigSource resolvedSource() {
        try {
            return java.util.ServiceLoader.load(LogConfigSource.class).findFirst().orElse(null);
        } catch (RuntimeException e) {
            logEarly("LogConfigSource lookup failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Opens a classpath resource with cascading classloader search:
     * <ol>
     *   <li>Thread context classloader — user application classpath
     *   <li>Own classloader — same JAR/module boundary
     *   <li>System classloader — JVM classpath
     * </ol>
     */
    private static InputStream openStream(String name) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (tccl != null) {
            InputStream in = tccl.getResourceAsStream(name);
            if (in != null) return in;
        }
        ClassLoader own = JULEnhancer.class.getClassLoader();
        if (own != null) {
            InputStream in = own.getResourceAsStream(name);
            if (in != null) return in;
        }
        return ClassLoader.getSystemResourceAsStream(name);
    }

    /**
     * Maps a config key to its environment variable name, honoring the
     * configurable env prefix ({@link EnvKeys#prefix()}, default
     * {@code FREEWAY_}) — the same one boot's cascade mapping uses.
     *
     * <p>Default prefix: {@code "freeway.log.level"} → {@code "FREEWAY_LOG_LEVEL"},
     * {@code "com.myapp.level"} → {@code "COM_MYAPP_LEVEL"}.
     * Custom prefix {@code "APP_"}: {@code "freeway.log.level"} →
     * {@code "APP_FREEWAY_LOG_LEVEL"} (the cascade maps that back to
     * {@code freeway.log.level}).</p>
     */
    static String envKeyFor(String configKey) {
        return EnvKeys.name(EnvKeys.prefix(), configKey);
    }

    /** The -D/env band of the cascade alone — no file homes: the system
     *  property, else the mapped environment variable, else null. The
     *  formatter/adapter feature flags read at class-load time use this;
     *  they share the env mapping with {@link #readProperty} but consult no
     *  {@link LogConfigSource}. */
    static String sysOrEnv(String key) {
        return System.getProperty(key, System.getenv(envKeyFor(key)));
    }

    /**
     * Reads a config value with cascading fallback:
     * <ol>
     *   <li>System property ({@code -Dkey=value}) — highest priority
     *   <li>Environment variable (prefix from {@link #envKeyFor}) — for {@code freeway.*} keys
     *   <li>File homes merged by {@link #loadLogConfig} — dedicated
     *       {@code freeway-logging.properties} first, then the application files
     *       (both ranked by the put order)
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
        // 3. File homes (dedicated file > app files) — one merged map; the
        //    put order in loadLogConfig ranks them.
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
            fileConfig, LogKeys.LEVEL, "INFO"
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
        // Only honor env vars whose key is ALSO configured in the file or
        // system properties: an unrelated *_LEVEL variable (LOG_LEVEL,
        // CI_LEVEL, ...) must not create a phantom logger or silently override
        // a logger's level. The env value itself still wins via readProperty's
        // cascade.
        Set<String> knownKeys = new HashSet<>(fileConfig.stringPropertyNames());
        System.getProperties().stringPropertyNames().forEach(knownKeys::add);
        for (String envName : System.getenv().keySet()) {
            String candidate = envToConfigKey(envName);
            if (candidate != null && knownKeys.contains(candidate)) {
                collectLevelKeys(levelKeys, List.of(candidate));
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
                    && !key.equals(LogKeys.LEVEL)
                    && !key.startsWith(LogKeys.PREFIX)
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
     * <p>Only {@code _} is a separator: it is what {@link #envKeyFor} produces
     * for a key's dots. Every other character — a hyphen above all — is part of
     * the key name and round-trips verbatim
     * ({@code FREEWAY_LOG_FILE_MAX-SIZE} → {@code freeway.log.file.max-size}).
     * Folding {@code -} into {@code .} here would be lossy: it would merge
     * {@code max-size} with {@code max.size}.
     */
    static String envToConfigKey(String envName) {
        String prefix = EnvKeys.prefix();
        String candidate;
        if (EnvKeys.DEFAULT_PREFIX.equals(prefix)) {
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
            fileConfig, LogKeys.CONSOLE_ENABLED, "true"
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
            fileConfig, LogKeys.CONSOLE_LEVEL, null
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
     * ({@code FREEWAY_LOG_FORMAT}), and {@code freeway-logging.properties}.
     */
    private static String formatMode(Properties fileConfig) {
        String v = readProperty(fileConfig, LogKeys.FORMAT, "auto");
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

    /**
     * One file, exactly one handler: returns the registered handler for
     * {@code path}, creating and registering a fresh one when absent. The
     * single place both the default file and every named file go through —
     * reuse (instead of a duplicate handler) is what keeps records out of
     * a second, independent rotation state on the same file.
     */
    private static JULFileHandler obtainFileHandler(
        String path,
        long maxSize,
        int maxHistory,
        boolean compress,
        long flushIntervalMs
    ) throws IOException {
        Path key = Paths.get(path).toAbsolutePath().normalize();
        JULFileHandler handler = registeredHandler(key);
        if (handler == null) {
            handler = new JULFileHandler(
                path, maxSize, maxHistory, compress, flushIntervalMs);
            registerHandler(key, handler);
        } else {
            logDedup(
                "Reusing existing handler for log file '" + path
                    + "' (same file already owned by another logger)");
        }
        return handler;
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
            JULFileHandler handler = obtainFileHandler(
                cfg.path, cfg.maxSize(), cfg.maxHistory(),
                cfg.compress(), cfg.flushIntervalMs());
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
        String raw = readProperty(fileConfig, LogKeys.FILE, "auto");

        if (!"off".equalsIgnoreCase(raw)) {
            String path;
            if ("auto".equalsIgnoreCase(raw)) {
                path = resolveDefaultPath();
            } else {
                path = raw;
            }

            try {
                FileSettings settings = fileSettings(
                    LogKeys.FILE, fileConfig);
                JULFileHandler fh = obtainFileHandler(
                    path, settings.maxSize(), settings.maxHistory(),
                    settings.compress(), settings.flushIntervalMs());
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
        String files = readProperty(fileConfig, LogKeys.FILES, null);
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
        String prefix = LogKeys.FILE_PREFIX + name;
        String path = readProperty(fileConfig, prefix + LogKeys.SUFFIX_PATH, null);

        if (path == null) {
            logEarly(
                "Skipping log file '" + name + "': "
                    + prefix + ".path is not set"
            );
            return;
        }

        Level level = null;
        String levelStr = readProperty(fileConfig, prefix + LogKeys.SUFFIX_LEVEL, null);
        if (levelStr != null) {
            try {
                level = parseLogLevel(levelStr);
            } catch (IllegalArgumentException e) {
                logEarly("Invalid level '" + levelStr + "' for log file '" + name + "': " + e.getMessage());
            }
        }

        String loggerName = readProperty(fileConfig, prefix + LogKeys.SUFFIX_LOGGER, null);
        FileSettings settings = fileSettings(prefix, fileConfig);
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
    private static FileSettings fileSettings(String prefix, Properties fileConfig) {
        return new FileSettings(
            propertyValue(fileConfig,
                prefix + LogKeys.SUFFIX_MAX_SIZE, JULFileHandler.DEFAULT_MAX_SIZE,
                Long::parseLong),
            propertyValue(fileConfig,
                prefix + LogKeys.SUFFIX_MAX_HISTORY, JULFileHandler.DEFAULT_MAX_HISTORY,
                Integer::parseInt),
            propertyValue(fileConfig,
                prefix + LogKeys.SUFFIX_COMPRESS, JULFileHandler.DEFAULT_COMPRESS,
                JULEnhancer::strictBoolean),
            propertyValue(fileConfig,
                prefix + LogKeys.SUFFIX_FLUSH_INTERVAL,
                JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS, Long::parseLong)
        );
    }

    /**
     * The cascade reader used for every {@code freeway.log.*} lookup —
     * {@code -D} > env > file > default (see {@link #readProperty}); value
     * parsing and its lenient/strict policy live in {@link #propertyValue}.
     */
    static Function<String, String> cascadeReader(Properties fileConfig) {
        return k -> readProperty(fileConfig, k, null);
    }

    /**
     * Strict boolean parser: {@code Boolean::parseBoolean} never throws, so
     * garbage input would silently map to {@code false} instead of triggering
     * the lenient fallback to the default. Rejecting anything but true/false
     * makes the lenient contract real for Boolean values.
     */
    static Boolean strictBoolean(String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(
            "Invalid boolean value: '" + value + "' (expected true or false)");
    }

    /**
     * Reads a config value via the full cascade ({@link #cascadeReader}) and
     * parses it with {@code parser}. An absent or blank value yields
     * {@code defaultValue}; a value that does not parse is <em>reported and
     * replaced</em> by {@code defaultValue}.
     *
     * <p>One policy, because the same key resolves through two paths — the
     * framework's bootstrap ({@link #fileSettings}) and a natively registered
     * handler ({@code JULFileHandler()}) — and they used to disagree: one fell
     * back in silence, the other threw. Neither is right on its own: throwing
     * during {@code LogManager} instantiation loses the handler, and silence is
     * how a typo becomes a wrong log level for a week. The notice names the key,
     * the value and the default actually used, so the fix needs no source
     * reading.</p>
     */
    static <T> T propertyValue(
        Properties fileConfig,
        String key,
        T defaultValue,
        Function<String, T> parser
    ) {
        String raw = readProperty(fileConfig, key, null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String value = raw.strip();
        try {
            return parser.apply(value);
        } catch (RuntimeException e) {
            logEarly(
                key + "='" + value + "' is not a valid value (" + e.getMessage()
                    + ") — using " + defaultValue
            );
            return defaultValue;
        }
    }
}
