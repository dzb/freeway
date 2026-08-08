package com.jujin.freeway.commons.logging;
import java.util.ArrayList;
import java.util.List;
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
}
