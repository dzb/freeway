package com.jujin.freeway.commons.logging;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

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
        try {
            Properties fileConfig = loadFreewayConfig();
            configureLevels(fileConfig);
            configureConsole(fileConfig);
            installFormatters();
            activateFileLogging(fileConfig);
            configured = true;
        } catch (RuntimeException e) {
            Logger.getLogger(JULEnhancer.class.getName())
                .severe("Failed to configure JUL logging: " + e);
        }
    }

    // ── config loading ──────────────────────────────────────────

    /**
     * Loads {@code freeway-log.properties} from the classpath root.
     * Returns an empty {@code Properties} if the file is not found.
     */
    private static Properties loadFreewayConfig() {
        Properties props = new Properties();
        try (InputStream in = JULEnhancer.class
                .getClassLoader()
                .getResourceAsStream("freeway-log.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            Logger.getLogger(JULEnhancer.class.getName()).warning(
                "Failed to load freeway-log.properties: " + e.getMessage()
            );
        }
        return props;
    }

    /**
     * Reads a config value with cascading fallback:
     * <ol>
     *   <li>System property ({@code -Dkey=value}) — highest priority
     *   <li>{@code freeway-log.properties} key
     *   <li>{@code defaultValue}
     * </ol>
     */
    private static String readProperty(
        Properties fileConfig, String key, String defaultValue
    ) {
        String sysVal = System.getProperty(key);
        if (sysVal != null) {
            String stripped = sysVal.strip();
            if (!stripped.isEmpty()) return stripped;
        }
        String fileVal = fileConfig.getProperty(key);
        if (fileVal != null) {
            String stripped = fileVal.strip();
            if (!stripped.isEmpty()) return stripped;
        }
        return defaultValue;
    }

    // ── levels ──────────────────────────────────────────────────

    private static void configureLevels(Properties fileConfig) {
        // Root logger level
        String rootLevel = readProperty(
            fileConfig, "freeway.log.level", "INFO"
        );
        Logger.getLogger("").setLevel(
            Level.parse(rootLevel.toUpperCase(Locale.ROOT))
        );

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
            if (effective == null || effective.isBlank()) continue;

            String loggerName = key.substring(
                0, key.length() - ".level".length()
            );
            Logger.getLogger(loggerName).setLevel(
                Level.parse(effective.toUpperCase(Locale.ROOT))
            );
        }
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
                    h.setLevel(
                        Level.parse(level.toUpperCase(Locale.ROOT))
                    );
                }
            }
        }

        if (!hasConsole) {
            ConsoleHandler ch = new ConsoleHandler();
            String level = readProperty(
                fileConfig, "freeway.log.console.level", "INFO"
            );
            ch.setLevel(Level.parse(level.toUpperCase(Locale.ROOT)));
            root.addHandler(ch);
        }
    }

    // ── formatter installation ──────────────────────────────────

    private static void installFormatters() {
        if ("simple".equalsIgnoreCase(formatMode())) {
            // Opt out: leave JUL's native SimpleFormatter in place.
            return;
        }
        // Auto (default): install Freeway's JUL formatters.
        JULConsoleFormatter consoleFmt = new JULConsoleFormatter();
        JULFileFormatter fileFmt = new JULFileFormatter();
        for (Handler h : Logger.getLogger("").getHandlers()) {
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
     */
    private static String formatMode() {
        String v = System.getProperty("freeway.log.format");
        if (v == null) v = System.getenv("FREEWAY_LOG_FORMAT");
        if (v == null || v.isBlank()) return "auto";
        String trimmed = v.strip();
        if ("auto".equalsIgnoreCase(trimmed) || "simple".equalsIgnoreCase(trimmed)) {
            return trimmed.toLowerCase();
        }
        Logger.getLogger("com.jujin.freeway.commons.logging").warning(
            "Unknown freeway.log.format '" + trimmed + "' — using 'auto'"
        );
        return "auto";
    }

    // ── file logging activation ─────────────────────────────────

    static synchronized void resetForTest() {
        configured = false;
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

        if (!("off".equalsIgnoreCase(raw) || "none".equalsIgnoreCase(raw))) {
            String path;
            if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw)) {
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
                    )
                );
                Logger.getLogger("").addHandler(fh);
            } catch (IOException | RuntimeException e) {
                Logger.getLogger(JULEnhancer.class.getName()).warning(
                    "Failed to activate file logging for '"
                        + path + "': " + e.getMessage()
                );
            }
        }

        // ── additional named files ──────────────────────────────
        String files = readProperty(fileConfig, "freeway.log.files", null);
        if (files == null || files.isBlank()) return;
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

        if (path == null || path.isBlank()) {
            Logger.getLogger(JULEnhancer.class.getName()).warning(
                "Skipping log file '" + name + "': "
                    + prefix + ".path is not set"
            );
            return;
        }

        try {
            JULFileHandler handler = new JULFileHandler(
                path,
                longProperty(
                    fileConfig, prefix + ".max-size",
                    JULFileHandler.DEFAULT_MAX_SIZE
                ),
                intProperty(
                    fileConfig, prefix + ".max-history",
                    JULFileHandler.DEFAULT_MAX_HISTORY
                ),
                booleanProperty(
                    fileConfig, prefix + ".compress",
                    JULFileHandler.DEFAULT_COMPRESS
                )
            );

            String level = readProperty(fileConfig, prefix + ".level", null);
            if (level != null && !level.isBlank()) {
                handler.setLevel(
                    Level.parse(level.strip().toUpperCase(Locale.ROOT))
                );
            }

            String loggerName = readProperty(
                fileConfig, prefix + ".logger", null
            );
            Logger target = (loggerName != null && !loggerName.isBlank())
                ? Logger.getLogger(loggerName.strip())
                : Logger.getLogger(""); // root
            target.addHandler(handler);
            // Prevent double-delivery: messages logged to this logger go
            // only to this file, not also to root/parent handlers.
            if (target.getParent() != null) {
                target.setUseParentHandlers(false);
            }

        } catch (IOException | RuntimeException e) {
            Logger.getLogger(JULEnhancer.class.getName()).warning(
                "Failed to activate named log file '"
                    + name + "' at '" + path + "': " + e.getMessage()
            );
        }
    }

    // ── property helpers ────────────────────────────────────────

    private static long longProperty(
        Properties fileConfig, String key, long defaultValue
    ) {
        String val = readProperty(fileConfig, key, null);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Long.parseLong(val.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int intProperty(
        Properties fileConfig, String key, int defaultValue
    ) {
        String val = readProperty(fileConfig, key, null);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val.strip());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean booleanProperty(
        Properties fileConfig, String key, boolean defaultValue
    ) {
        String val = readProperty(fileConfig, key, null);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val.strip());
    }
}
