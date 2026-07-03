package com.jujin.freeway.commons.logging;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Configures JUL with Freeway's console and file logging enhancements.
 * Called at startup regardless of which SLF4J provider is active —
 * these enhancements are pure JDK and do not interfere with SLF4J.
 */
final class JULEnhancer {

    private static volatile boolean configured;

    private JULEnhancer() {}

    static synchronized void configure() {
        if (configured) return;
        try {
            loadClasspathConfig();
            installFormatters();
            activateFileLogging();
            configured = true;
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger(JULEnhancer.class.getName())
                    .severe("Failed to configure JUL logging: " + e);
        }
    }

    // ── classpath config ────────────────────────────────────────────

    private static void loadClasspathConfig() {
        if (System.getProperty("java.util.logging.config.file") != null) {
            return; // explicit override — user controls it
        }
        try (InputStream in = JULEnhancer.class.getClassLoader()
                .getResourceAsStream("logging.properties")) {
            if (in != null) {
                LogManager.getLogManager().updateConfiguration(in, null);
            }
        } catch (IOException e) {
            Logger.getLogger(JULEnhancer.class.getName())
                    .warning("Failed to load logging.properties from classpath: " + e);
        }
    }

    // ── formatter installation ──────────────────────────────────────

    private static void installFormatters() {
        String format = formatOverride();
        if (format != null) {
            String trimmed = format.strip();
            if ("simple".equalsIgnoreCase(trimmed)) return;
            if (!trimmed.isBlank()) {
                Logger.getLogger("com.jujin.freeway.commons.logging")
                        .warning("Unknown freeway.log.format '" + trimmed + "' — ignoring");
            }
        }

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

    private static String formatOverride() {
        String v = System.getProperty("freeway.log.format");
        if (v != null) return v;
        return System.getenv("FREEWAY_LOG_FORMAT");
    }

    // ── file logging activation ─────────────────────────────────────

    static synchronized void resetForTest() {
        configured = false;
        Logger root = Logger.getLogger("");
        for (Handler handler : root.getHandlers()) {
            if (handler instanceof JULFileHandler || handler instanceof FileHandler) {
                root.removeHandler(handler);
                try {
                    handler.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void activateFileLogging() {
        String path = System.getProperty("freeway.log.file");
        if (path == null || path.isBlank()) return;

        if ("auto".equalsIgnoreCase(path.strip())) {
            String appName = System.getProperty("app.name");
            if (appName == null || appName.isBlank()) appName = "freeway";
            path = "logs/" + appName + ".log";
        }

        try {
            JULFileHandler fh = new JULFileHandler(path,
                    longProperty("freeway.log.file.max-size", JULFileHandler.DEFAULT_MAX_SIZE),
                    intProperty("freeway.log.file.max-history", JULFileHandler.DEFAULT_MAX_HISTORY),
                    booleanProperty("freeway.log.file.compress", JULFileHandler.DEFAULT_COMPRESS));
            Logger.getLogger("").addHandler(fh);
        } catch (IOException e) {
            Logger.getLogger(JULEnhancer.class.getName())
                    .warning("Failed to activate file logging: " + e);
        }
    }

    private static long longProperty(String key, long defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Long.parseLong(val.strip()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static int intProperty(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Integer.parseInt(val.strip()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val.strip());
    }
}
