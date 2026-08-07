package com.jujin.freeway.commons.logging;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
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
 */
final class JULEnhancer {

    private static volatile boolean configured;

    private JULEnhancer() {}

    static synchronized void configure() {
        if (configured) return;

        // Force JUL LogManager initialization BEFORE attaching handlers.
        // LogManager is lazily initialized — ensureInitialized() calls
        // readConfiguration() which calls reset(), removing all handlers
        // from all existing loggers. By triggering it here, the reset
        // happens before our handlers are attached and survives.
        LogManager.getLogManager().getLoggerNames();

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
     * Maps a {@code freeway.*} config key to its environment variable name,
     * honoring the configurable env prefix ({@code freeway.env.prefix},
     * default {@code FREEWAY_}) — consistent with
     * {@code ConfigLoaderDefault}'s cascade mapping.
     *
     * <p>Default prefix: {@code "freeway.log.level"} → {@code "FREEWAY_LOG_LEVEL"}.
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
        //    or APP_FREEWAY_LOG_LEVEL under a custom prefix)
        if (key.startsWith("freeway.")) {
            String envVal = System.getenv(envKeyFor(key));
            if (envVal != null) {
                String stripped = envVal.strip();
                if (!stripped.isEmpty()) return stripped;
            }
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

        // Collect all .level keys from file config and system properties
        Set<String> levelKeys = new HashSet<>();
        for (String key : fileConfig.stringPropertyNames()) {
            if (key.endsWith(".level") && !key.equals("freeway.log.level")) {
                levelKeys.add(key);
            }
        }
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.endsWith(".level") && !key.equals("freeway.log.level")) {
                levelKeys.add(key);
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
            // Remove all ConsoleHandlers
            for (Handler h : root.getHandlers()) {
                if (h instanceof ConsoleHandler) {
                    root.removeHandler(h);
                    h.close();
                }
            }
            return;
        }

        // Ensure at least one ConsoleHandler exists
        boolean hasConsole = false;
        for (Handler h : root.getHandlers()) {
            if (h instanceof ConsoleHandler) {
                hasConsole = true;
                String level = readProperty(
                    fileConfig, "freeway.log.console.level", null
                );
                if (level != null) {
                    try {
                        h.setLevel(parseLogLevel(level));
                    } catch (IllegalArgumentException e) {
                        logEarly("Invalid console level '" + level + "': " + e.getMessage());
                    }
                }
            }
        }

        if (!hasConsole) {
            ConsoleHandler ch = new ConsoleHandler();
            freewayHandlers.add(ch);
            String level = readProperty(
                fileConfig, "freeway.log.console.level", "INFO"
            );
            try {
                ch.setLevel(parseLogLevel(level));
            } catch (IllegalArgumentException e) {
                logEarly("Invalid console level '" + level + "': " + e.getMessage());
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
            if (h instanceof FileHandler || h instanceof JULFileHandler) {
                h.setFormatter(fileFmt);
            } else {
                h.setFormatter(consoleFmt);
            }
        }
        // Also upgrade JUL's stock root handlers (e.g. the JVM default console
        // handler): their formatter is the unmodified SimpleFormatter, so the
        // user has not customized it. Handlers carrying a non-default
        // formatter are left untouched.
        for (Handler h : Logger.getLogger("").getHandlers()) {
            if (freewayHandlers.contains(h)) {
                continue;
            }
            Formatter fmt = h.getFormatter();
            boolean stock = fmt == null || fmt instanceof SimpleFormatter;
            if (!stock) {
                continue;
            }
            if (h instanceof FileHandler || h instanceof JULFileHandler) {
                h.setFormatter(fileFmt);
            } else {
                h.setFormatter(consoleFmt);
            }
        }
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
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Re-applies all named file handler configurations. A no-op after
     * the first call — an internal guard prevents duplicate handlers.
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
            JULFileHandler handler = new JULFileHandler(
                cfg.path,
                cfg.maxSize,
                cfg.maxHistory,
                cfg.compress,
                cfg.flushIntervalMs
            );
            freewayHandlers.add(handler);
            if (cfg.level != null) handler.setLevel(cfg.level);

            Logger target = (cfg.loggerName != null)
                ? Logger.getLogger(cfg.loggerName)
                : Logger.getLogger(""); // root
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

        if (!"off".equalsIgnoreCase(raw)) {
            String path;
            if ("auto".equalsIgnoreCase(raw)) {
                path = resolveDefaultPath();
            } else {
                path = raw;
            }

            try {
                JULFileHandler fh = new JULFileHandler(
                    path,
                    longProperty(
                        fileConfig,
                        "freeway.log.file.max-size",
                        JULFileHandler.DEFAULT_MAX_SIZE
                    ),
                    intProperty(
                        fileConfig,
                        "freeway.log.file.max-history",
                        JULFileHandler.DEFAULT_MAX_HISTORY
                    ),
                    booleanProperty(
                        fileConfig,
                        "freeway.log.file.compress",
                        JULFileHandler.DEFAULT_COMPRESS
                    ),
                    longProperty(
                        fileConfig,
                        "freeway.log.file.flush-interval",
                        JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS
                    )
                );
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
        NamedFileConfig cfg = new NamedFileConfig(
            path,
            longProperty(fileConfig, prefix + ".max-size", JULFileHandler.DEFAULT_MAX_SIZE),
            intProperty(fileConfig, prefix + ".max-history", JULFileHandler.DEFAULT_MAX_HISTORY),
            booleanProperty(fileConfig, prefix + ".compress", JULFileHandler.DEFAULT_COMPRESS),
            longProperty(
                fileConfig,
                prefix + ".flush-interval",
                JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS
            ),
            level,
            loggerName
        );
        namedFileConfigs.add(cfg);
        attachNamedFile(cfg);
    }

    // ── property helpers ────────────────────────────────────────

    private static long longProperty(
        Properties fileConfig, String key, long defaultValue
    ) {
        String val = readProperty(fileConfig, key, null);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int intProperty(
        Properties fileConfig, String key, int defaultValue
    ) {
        String val = readProperty(fileConfig, key, null);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean booleanProperty(
        Properties fileConfig, String key, boolean defaultValue
    ) {
        String val = readProperty(fileConfig, key, null);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val);
    }
}
