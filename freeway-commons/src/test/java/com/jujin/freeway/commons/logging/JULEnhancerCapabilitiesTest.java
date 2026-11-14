package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Capabilities that the configuration surface exposes but the older tests did
 * not exercise end to end: {@code format=simple}, {@code file=off}, lenient
 * fallback for malformed file settings, named-file level filtering, whitespace
 * in the file list, and rotation settings reaching the handler created for the
 * default file.
 */
class JULEnhancerCapabilitiesTest {

    private static final List<String> KEYS = List.of(
        "freeway.log.level",
        "freeway.log.format",
        "freeway.log.file",
        "freeway.log.files",
        "freeway.log.file.max-size",
        "freeway.log.file.max-history",
        "freeway.log.file.compress",
        "freeway.log.file.flush-interval",
        "freeway.log.file.cap.path",
        "freeway.log.file.cap.logger",
        "freeway.log.file.cap.level",
        "freeway.log.file.cap.flush-interval",
        "freeway.log.file.ws.path",
        "freeway.log.file.ws.logger",
        "freeway.log.file.ws.flush-interval");

    @BeforeEach
    void clearProperties() {
        KEYS.forEach(System::clearProperty);
        // A previous class may have configured JUL already; start from a clean
        // enhancer state so configure() in this class actually runs.
        JULEnhancer.resetForTest();
    }

    @AfterEach
    void restore() {
        KEYS.forEach(System::clearProperty);
        JULEnhancer.resetForTest();
    }

    @Test
    void simpleFormatKeepsJulNativeFormatter() {
        Logger root = Logger.getLogger("");
        Handler[] saved = root.getHandlers();
        try {
            removeAll(root);
            System.setProperty("freeway.log.format", "simple");
            System.setProperty("freeway.log.file", "off");
            JULEnhancer.configure();

            ConsoleHandler console = (ConsoleHandler) Arrays.stream(root.getHandlers())
                .filter(ConsoleHandler.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("console handler expected"));
            assertInstanceOf(SimpleFormatter.class, console.getFormatter(),
                "format=simple is the opt-out: JUL's own formatter stays");
        } finally {
            removeAll(root);
            for (Handler handler : saved) {
                root.addHandler(handler);
            }
        }
    }

    @Test
    void fileOffAttachesNoFreewayFileHandler() {
        System.setProperty("freeway.log.file", "off");
        JULEnhancer.configure();

        assertFalse(Arrays.stream(Logger.getLogger("").getHandlers())
                .anyMatch(JULFileHandler.class::isInstance),
            "freeway.log.file=off must leave no file handler behind");
    }

    @Test
    void malformedFileSettingsFallBackWithoutAborting(@TempDir Path tempDir) throws IOException {
        Path log = tempDir.resolve("malformed.log");
        System.setProperty("freeway.log.file", log.toString());
        System.setProperty("freeway.log.file.max-size", "not-a-number");
        System.setProperty("freeway.log.file.max-history", "still-not");
        System.setProperty("freeway.log.file.compress", "maybe");
        System.setProperty("freeway.log.file.flush-interval", "nope");

        assertDoesNotThrow(JULEnhancer::configure);
        assertTrue(Arrays.stream(Logger.getLogger("").getHandlers())
                .anyMatch(JULFileHandler.class::isInstance),
            "the handler still exists with default settings");

        var slf4j = org.slf4j.LoggerFactory.getLogger("cap.malformed");
        slf4j.info("survives malformed settings");
        flushFileHandlers();
        assertTrue(Files.readString(logFile(tempDir)).contains("survives malformed settings"));
    }

    @Test
    void namedFileLevelFiltersLowerRecords(@TempDir Path tempDir) throws IOException {
        Path log = tempDir.resolve("cap.log");
        System.setProperty("freeway.log.file", "off");
        System.setProperty("freeway.log.files", "cap");
        System.setProperty("freeway.log.file.cap.path", log.toString());
        System.setProperty("freeway.log.file.cap.logger", "com.example.cap");
        System.setProperty("freeway.log.file.cap.level", "WARNING");
        System.setProperty("freeway.log.file.cap.flush-interval", "0");
        JULEnhancer.configure();

        var logger = org.slf4j.LoggerFactory.getLogger("com.example.cap");
        logger.info("info-line");
        logger.warn("warn-line");
        flushFileHandlers();

        String content = Files.readString(log);
        assertTrue(content.contains("warn-line"), content);
        assertFalse(content.contains("info-line"),
            "the named file's level must filter lower records: " + content);
    }

    @Test
    void fileListToleratesWhitespaceAndEmptyEntries(@TempDir Path tempDir) {
        Path log = tempDir.resolve("ws.log");
        System.setProperty("freeway.log.file", "off");
        System.setProperty("freeway.log.files", " ws , , missing ");
        System.setProperty("freeway.log.file.ws.path", log.toString());
        System.setProperty("freeway.log.file.ws.logger", "com.example.ws");
        System.setProperty("freeway.log.file.ws.flush-interval", "0");

        assertDoesNotThrow(JULEnhancer::configure);
        Logger target = Logger.getLogger("com.example.ws");
        assertTrue(Arrays.stream(target.getHandlers())
                .anyMatch(JULFileHandler.class::isInstance),
            "the trimmed name attaches its handler; blank/missing entries are skipped");
    }

    @Test
    void rotationSettingsReachTheDefaultFileHandler(@TempDir Path tempDir) throws IOException {
        Path log = tempDir.resolve("rotate.log");
        System.setProperty("freeway.log.file", log.toString());
        System.setProperty("freeway.log.file.max-size", "600");
        System.setProperty("freeway.log.file.max-history", "3");
        System.setProperty("freeway.log.file.compress", "true");
        System.setProperty("freeway.log.file.flush-interval", "0");
        JULEnhancer.configure();

        var logger = org.slf4j.LoggerFactory.getLogger("cap.rotation");
        for (int i = 0; i < 40; i++) {
            logger.info("rotation line {} with some payload to pass the size threshold", i);
        }
        flushFileHandlers();

        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".log")),
                "the live log file exists");
        }
        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".gz")),
                "max-size/compress from the config reached the handler; "
                    + "a rotated archive is expected");
        }
    }

    private static Path logFile(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".log"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("log file expected"));
        }
    }

    private static void flushFileHandlers() {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler instanceof JULFileHandler) {
                handler.flush();
            }
        }
        Logger.getLogger("com.example.cap").getHandlers();
        for (String name : new String[]{"com.example.cap", "com.example.ws"}) {
            for (Handler handler : Logger.getLogger(name).getHandlers()) {
                handler.flush();
            }
        }
    }

    private static void removeAll(Logger logger) {
        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
            handler.close();
        }
    }
}
