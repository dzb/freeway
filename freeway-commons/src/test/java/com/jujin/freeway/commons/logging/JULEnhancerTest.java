package com.jujin.freeway.commons.logging;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.LogManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JULEnhancerTest {

    @Test
    void envKeyForUsesDefaultPrefix() {
        assertEquals("FREEWAY_LOG_LEVEL",
            JULEnhancer.envKeyFor("freeway.log.level"));
        assertEquals("FREEWAY_LOG_FILE_MAX_SIZE",
            JULEnhancer.envKeyFor("freeway.log.file.max.size"));
        assertEquals("FREEWAY_LOG_COLOR",
            JULEnhancer.envKeyFor("freeway.log.color"));
    }

    @Test
    void envKeyForHonorsCustomPrefix() {
        String prev = System.getProperty("freeway.env.prefix");
        try {
            System.setProperty("freeway.env.prefix", "APP_");
            // Custom prefix wraps the full key: APP_FREEWAY_LOG_LEVEL →
            // (cascade, strip APP_) FREEWAY_LOG_LEVEL → freeway.log.level.
            assertEquals("APP_FREEWAY_LOG_LEVEL",
                JULEnhancer.envKeyFor("freeway.log.level"));
            assertEquals("APP_FREEWAY_LOG_COLOR",
                JULEnhancer.envKeyFor("freeway.log.color"));
        } finally {
            if (prev == null) {
                System.clearProperty("freeway.env.prefix");
            } else {
                System.setProperty("freeway.env.prefix", prev);
            }
        }
    }

    // ── regression: named file handlers are deduplicated by path ────

    @Test
    void applyNamedFileConfigsDoesNotDoubleAttach(@TempDir Path tempDir)
            throws IOException {
        String[] keys = {
            "freeway.log.file",
            "freeway.log.files",
            "freeway.log.file.dedup.path",
            "freeway.log.file.dedup.logger",
            "freeway.log.file.dedup.level",
            "freeway.log.file.dedup.flush-interval"
        };
        Path logFile = tempDir.resolve("dedup.log");
        System.setProperty("freeway.log.file", "off");
        System.setProperty("freeway.log.files", "dedup");
        System.setProperty("freeway.log.file.dedup.path", logFile.toString());
        System.setProperty("freeway.log.file.dedup.logger", "com.example.dedup");
        System.setProperty("freeway.log.file.dedup.level", "ALL");
        System.setProperty("freeway.log.file.dedup.flush-interval", "0");
        try {
            JULEnhancer.resetForTest();
            JULEnhancer.configure();

            Logger target = Logger.getLogger("com.example.dedup");
            assertEquals(1, countFileHandlers(target),
                "configure() should attach exactly one handler");

            // Re-application after startup must not attach a second handler
            // for the same file — including repeated calls.
            JULEnhancer.applyNamedFileConfigs();
            JULEnhancer.applyNamedFileConfigs();
            assertEquals(1, countFileHandlers(target),
                "applyNamedFileConfigs() must not double-attach");

            // Each record must be written exactly once (5 records → 5 lines).
            for (int i = 0; i < 5; i++) {
                target.log(Level.INFO, "dedup message " + i);
            }
            assertEquals(5, Files.readString(logFile).lines().count(),
                "Records must be written exactly once, not doubled");
        } finally {
            for (String key : keys) {
                System.clearProperty(key);
            }
            JULEnhancer.resetForTest();
        }
    }

    private static int countFileHandlers(Logger logger) {
        int count = 0;
        for (Handler h : logger.getHandlers()) {
            if (h instanceof JULFileHandler) {
                count++;
            }
        }
        return count;
    }

    private static JULFileHandler firstFileHandler(Logger logger) {
        for (Handler h : logger.getHandlers()) {
            if (h instanceof JULFileHandler fh) {
                return fh;
            }
        }
        return null;
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ── regression: same file on two loggers shares ONE handler ────

    @Test
    void twoNamedFilesOnSamePathShareOneHandler(@TempDir Path tempDir)
            throws IOException {
        String[] keys = {
            "freeway.log.file",
            "freeway.log.files",
            "freeway.log.file.a.path",
            "freeway.log.file.a.logger",
            "freeway.log.file.a.level",
            "freeway.log.file.a.flush-interval",
            "freeway.log.file.b.path",
            "freeway.log.file.b.logger",
            "freeway.log.file.b.level",
            "freeway.log.file.b.flush-interval"
        };
        Path logFile = tempDir.resolve("shared.log");
        System.setProperty("freeway.log.file", "off");
        System.setProperty("freeway.log.files", "a,b");
        System.setProperty("freeway.log.file.a.path", logFile.toString());
        System.setProperty("freeway.log.file.a.logger", "com.example.a");
        System.setProperty("freeway.log.file.a.level", "ALL");
        System.setProperty("freeway.log.file.a.flush-interval", "0");
        System.setProperty("freeway.log.file.b.path", logFile.toString());
        System.setProperty("freeway.log.file.b.logger", "com.example.b");
        System.setProperty("freeway.log.file.b.level", "ALL");
        System.setProperty("freeway.log.file.b.flush-interval", "0");
        try {
            JULEnhancer.resetForTest();
            JULEnhancer.configure();

            Logger a = Logger.getLogger("com.example.a");
            Logger b = Logger.getLogger("com.example.b");
            assertEquals(1, countFileHandlers(a),
                "logger a must have exactly one file handler");
            assertEquals(1, countFileHandlers(b),
                "logger b must have exactly one file handler");
            // Two loggers on one file must share the SAME handler — two
            // independent handlers would rotate the shared file against each
            // other (records silently moved into archives).
            assertSame(firstFileHandler(a), firstFileHandler(b),
                "both loggers must share one handler for one file");

            a.info("from a");
            b.info("from b");
            String content = Files.readString(logFile);
            assertEquals(1, occurrences(content, "from a"),
                "records from logger a must be written exactly once");
            assertEquals(1, occurrences(content, "from b"),
                "records from logger b must be written exactly once");
        } finally {
            for (String key : keys) {
                System.clearProperty(key);
            }
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void defaultFileAndNamedFileOnSamePathShareOneHandler(@TempDir Path tempDir)
            throws IOException {
        String[] keys = {
            "freeway.log.file",
            "freeway.log.files",
            "freeway.log.file.a.path",
            "freeway.log.file.a.logger",
            "freeway.log.file.a.level",
            "freeway.log.file.a.flush-interval",
            "freeway.log.file.flush-interval"
        };
        Path logFile = tempDir.resolve("shared-default.log");
        System.setProperty("freeway.log.file", logFile.toString());
        System.setProperty("freeway.log.file.flush-interval", "0");
        System.setProperty("freeway.log.files", "a");
        System.setProperty("freeway.log.file.a.path", logFile.toString());
        System.setProperty("freeway.log.file.a.logger", "com.example.a");
        System.setProperty("freeway.log.file.a.level", "ALL");
        System.setProperty("freeway.log.file.a.flush-interval", "0");
        try {
            JULEnhancer.resetForTest();
            JULEnhancer.configure();

            Logger root = Logger.getLogger("");
            Logger a = Logger.getLogger("com.example.a");
            assertEquals(1, countFileHandlers(root),
                "root must have exactly one file handler");
            assertEquals(1, countFileHandlers(a),
                "named logger must have exactly one file handler");
            // Default file and named file aliasing the same path must share
            // the single handler for that file.
            assertSame(firstFileHandler(root), firstFileHandler(a),
                "default and named file on the same path must share one handler");
        } finally {
            for (String key : keys) {
                System.clearProperty(key);
            }
            JULEnhancer.resetForTest();
        }
    }

    // ── regression: console level only applies to Freeway's handlers ──

    @Test
    void consoleLevelDoesNotOverrideUserConsoleHandler() {
        String[] keys = {
            "freeway.log.console.level",
            "freeway.log.file"
        };
        System.setProperty("freeway.log.console.level", "FINE");
        System.setProperty("freeway.log.file", "off");
        try {
            JULEnhancer.resetForTest();
            Logger root = Logger.getLogger("");
            ConsoleHandler userHandler = new ConsoleHandler();
            userHandler.setLevel(Level.WARNING);
            root.addHandler(userHandler);
            try {
                JULEnhancer.configure();
                assertEquals(Level.WARNING, userHandler.getLevel(),
                    "user-configured ConsoleHandler level must not be overridden");
            } finally {
                root.removeHandler(userHandler);
            }
        } finally {
            for (String key : keys) {
                System.clearProperty(key);
            }
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void consoleLevelAppliesToFreewayCreatedHandler() {
        String[] keys = {
            "freeway.log.console.level",
            "freeway.log.file"
        };
        System.setProperty("freeway.log.console.level", "FINE");
        System.setProperty("freeway.log.file", "off");
        try {
            JULEnhancer.resetForTest();
            Logger root = Logger.getLogger("");
            // Force LogManager initialization first so configure() does not
            // re-inject the JRE default ConsoleHandler after we remove it,
            // then clear every existing console handler so Freeway creates
            // its own.
            LogManager.getLogManager().getLoggerNames();
            List<Handler> removed = new ArrayList<>();
            for (Handler h : List.of(root.getHandlers())) {
                if (h instanceof ConsoleHandler) {
                    root.removeHandler(h);
                    removed.add(h);
                }
            }
            try {
                JULEnhancer.configure();
                boolean found = false;
                for (Handler h : root.getHandlers()) {
                    if (h instanceof ConsoleHandler) {
                        assertEquals(Level.FINE, h.getLevel(),
                            "Freeway-created ConsoleHandler must get the configured level");
                        found = true;
                    }
                }
                assertTrue(found,
                    "Freeway should create a ConsoleHandler when none exists");
            } finally {
                for (Handler h : removed) {
                    root.addHandler(h);
                }
            }
        } finally {
            for (String key : keys) {
                System.clearProperty(key);
            }
            JULEnhancer.resetForTest();
        }
    }

    // ====================== regression fixes ======================

    @Test
    void envToConfigKeyMapsBothPrefixes() {
        assertEquals("freeway.log.level", JULEnhancer.envToConfigKey("FREEWAY_LOG_LEVEL"));
        assertEquals("com.myapp.audit.level", JULEnhancer.envToConfigKey("COM_MYAPP_AUDIT_LEVEL"));
        assertEquals("path", JULEnhancer.envToConfigKey("PATH"));
        System.setProperty("freeway.env.prefix", "APP_");
        try {
            assertEquals("freeway.log.level", JULEnhancer.envToConfigKey("APP_FREEWAY_LOG_LEVEL"));
            assertNull(JULEnhancer.envToConfigKey("FREEWAY_LOG_LEVEL"),
                "custom prefix: non-prefixed env vars are out of scope");
        } finally {
            System.clearProperty("freeway.env.prefix");
        }
    }

    @Test
    void frameworkLevelKeysDoNotCreatePhantomLoggers() {
        // freeway.log.console.level must configure the console handler, not
        // create a JUL logger named "freeway.log.console".
        System.setProperty("freeway.log.console.level", "INFO");
        System.setProperty("freeway.log.file.audit.level", "FINE");
        try {
            JULEnhancer.resetForTest();
            JULEnhancer.configure();
            LogManager lm = LogManager.getLogManager();
            List<String> names = new ArrayList<>();
            var it = lm.getLoggerNames();
            while (it.hasMoreElements()) {
                names.add(it.nextElement());
            }
            assertFalse(names.contains("freeway.log.console"));
            assertFalse(names.contains("freeway.log.file.audit"));
        } finally {
            System.clearProperty("freeway.log.console.level");
            System.clearProperty("freeway.log.file.audit.level");
            JULEnhancer.resetForTest();
        }
    }

    // ── ownership contract: enabled=false must not touch customized handlers ──

    @Test
    void consoleDisabledKeepsCustomizedHandlerRemovesStockOne() {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        ConsoleHandler custom = new ConsoleHandler();
        Formatter marker = new Formatter() {
            @Override public String format(java.util.logging.LogRecord r) {
                return "custom";
            }
        };
        custom.setFormatter(marker);
        ConsoleHandler stock = new ConsoleHandler();
        root.addHandler(custom);
        root.addHandler(stock);
        System.setProperty("freeway.log.console.enabled", "false");
        System.setProperty("freeway.log.file", "off");
        try {
            JULEnhancer.resetForTest();
            JULEnhancer.configure();

            boolean customKept = false;
            for (Handler h : root.getHandlers()) {
                if (h == custom) {
                    customKept = true;
                }
            }
            assertTrue(customKept,
                "a customized ConsoleHandler is user configuration and survives enabled=false");
            for (Handler h : root.getHandlers()) {
                assertFalse(h == stock,
                    "stock ConsoleHandlers are JVM defaults and are removed by enabled=false");
            }
        } finally {
            root.removeHandler(custom);
            for (Handler h : root.getHandlers()) {
                if (h == stock) {
                    root.removeHandler(stock);
                }
            }
            System.clearProperty("freeway.log.console.enabled");
            System.clearProperty("freeway.log.file");
            JULEnhancer.resetForTest();
        }
    }

    // ── env reverse mapping reconciles dashed keys ──────────────────

    @Test
    void resolveConfigKeyReconcilesFoldedEnvCandidates() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("freeway.log.file.max-size", "100");

        assertEquals("freeway.log.file.max-size",
            JULEnhancer.resolveConfigKey("freeway.log.file.max.size", props),
            "FREEWAY_LOG_FILE_MAX_SIZE folds to max.size and must find the real dashed key");
        assertNull(JULEnhancer.resolveConfigKey("totally.unrelated.level", props),
            "no known key matches → no phantom logger");
    }
}

