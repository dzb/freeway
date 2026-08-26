package com.jujin.freeway.commons.logging;
import java.util.ArrayList;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LogBootstrap} and {@link JULLoggerServiceProvider} classpath config loading.
 *
 * <p>These tests must be isolated because they touch global SLF4J and JUL state.
 * Run with: mvn -pl freeway-commons test -Dtest=LogBootstrapTest
 */
class LogBootstrapTest {

    @BeforeAll
    static void setUp() {
        LogBootstrap.ensureProvider();
    }

    @Test
    void ensureProviderIsIdempotent() {
        LogBootstrap.ensureProvider();
        LogBootstrap.ensureProvider();

        Logger log = LoggerFactory.getLogger(LogBootstrapTest.class);
        assertNotNull(log);
        log.info("LogBootstrap idempotency check passed");
    }

    @Test
    void slf4jReturnsWorkingLoggerAfterBootstrap() {
        Logger log = LoggerFactory.getLogger("test.after.bootstrap");
        assertNotNull(log);
        assertTrue(log.isInfoEnabled());
    }

    @Test
    void julProviderInitializesCorrectly() {
        JULLoggerServiceProvider provider = new JULLoggerServiceProvider();
        provider.initialize();

        assertNotNull(provider.getLoggerFactory());
        assertNotNull(provider.getMarkerFactory());
        assertNotNull(provider.getMDCAdapter());
        assertEquals("2.0.17", provider.getRequestedApiVersion());
    }

    @Test
    void classpathConfigAutoLoadsLoggingProperties() {
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        assertNotNull(root);

        assertEquals(Level.INFO, root.getLevel(),
            "Root logger level should be INFO per logging.properties");

        Handler[] handlers = root.getHandlers();
        if (handlers.length > 0 && handlers[0].getFormatter() != null) {
            assertInstanceOf(JULConsoleFormatter.class, handlers[0].getFormatter(),
                "Console handler should use JULConsoleFormatter");
        }
    }

    @Test
    void julLoggerFactoryCreatesWorkingLoggers() {
        JULLoggerFactory factory = new JULLoggerFactory();
        org.slf4j.Logger log1 = factory.getLogger("test.a");
        org.slf4j.Logger log2 = factory.getLogger("test.a");
        org.slf4j.Logger log3 = factory.getLogger("test.b");

        assertNotNull(log1);
        assertNotNull(log2);
        assertNotNull(log3);
        assertSame(log1, log2);
        assertNotSame(log1, log3);
    }

    @Test
    void loggingAfterBootstrapDoesNotThrow() {
        Logger log = LoggerFactory.getLogger("smoke.test");
        log.trace("trace message");
        log.debug("debug message");
        log.info("info message");
        log.warn("warn message");
        log.error("error message", new RuntimeException("test exception"));

        assertTrue(true);
    }

    @Test
    void fileHandlerUsesJULFileFormatter(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("app.log");

        java.util.logging.Logger julLogger = java.util.logging.Logger.getLogger("file.test");
        FileHandler fileHandler = new FileHandler(logFile.toString());
        fileHandler.setFormatter(new JULFileFormatter());
        fileHandler.setLevel(Level.INFO);
        julLogger.setLevel(Level.INFO);
        julLogger.setUseParentHandlers(false);
        julLogger.addHandler(fileHandler);

        Logger log = LoggerFactory.getLogger("file.test");
        log.info("line one");
        log.warn("line two");
        log.error("line three", new RuntimeException("test"));

        fileHandler.flush();
        fileHandler.close();

        String content = Files.readString(logFile);
        assertTrue(content.contains("line one"), "File should contain info message: " + content);
        assertTrue(content.contains("line two"), "File should contain warn message: " + content);
        assertTrue(content.contains("line three"), "File should contain error message: " + content);
        assertTrue(content.contains("RuntimeException"),
            "File should contain exception class name: " + content);
    }

    @Test
    void fullFlowConsoleAndFileOutput(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("demo.log");
        System.setProperty("freeway.log.file", logFile.toString());
        System.setProperty("app.name", "demo");
        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            Logger log = LoggerFactory.getLogger("demo.fullflow");
            log.info("hello 控制台 + 文件");
            log.warn("警告 {}", "详情");
            log.error("异常日志", new RuntimeException("demo error"));

            // File publish is batched by default — flush before reading
            for (var h : java.util.logging.Logger.getLogger("").getHandlers()) {
                if (h instanceof JULFileHandler) h.flush();
            }

            String fileContent = Files.readString(logFile);
            assertTrue(fileContent.contains("hello 控制台 + 文件"),
                    "文件应含 info 消息: " + fileContent);
            assertTrue(fileContent.contains("警告 详情"),
                    "文件应含 warn 消息 ({}) 替换: " + fileContent);
            assertTrue(fileContent.contains("SEVERE"),
                    "文件应含 SEVERE 级别: " + fileContent);
            assertTrue(fileContent.contains("demo error"),
                    "文件应含异常消息: " + fileContent);
            assertTrue(fileContent.contains("RuntimeException"),
                    "文件应含异常类名: " + fileContent);
            assertTrue(fileContent.contains("[main]"),
                    "文件应含线程名 [main]: " + fileContent);
        } finally {
            System.clearProperty("freeway.log.file");
            System.clearProperty("app.name");
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void enhancerRecoversAfterRuntimeException() {
        String saved = System.getProperty("freeway.log.file");
        System.setProperty("freeway.log.file", "\0illegal");
        JULEnhancer.resetForTest();
        try {
            assertDoesNotThrow(() -> JULEnhancer.configure(),
                "configure() should not crash on bad config, just log");
        } finally {
            if (saved != null) System.setProperty("freeway.log.file", saved);
            else System.clearProperty("freeway.log.file");
            JULEnhancer.resetForTest();
        }
    }

    // ── multi-file logging ────────────────────────────────────

    @Test
    void multiFileCreatesHandlersOnCorrectLoggers(@TempDir Path tempDir) {
        Path bizPath = tempDir.resolve("biz.log");
        Path auditPath = tempDir.resolve("audit.log");

        System.setProperty("freeway.log.file", "off"); // no default file
        System.setProperty("freeway.log.files", "biz,audit");
        System.setProperty("freeway.log.file.biz.path", bizPath.toString());
        System.setProperty("freeway.log.file.audit.path", auditPath.toString());
        System.setProperty("freeway.log.file.audit.logger", "com.myapp.audit");

        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            java.util.logging.Logger root =
                java.util.logging.Logger.getLogger("");
            java.util.logging.Logger auditLogger =
                java.util.logging.Logger.getLogger("com.myapp.audit");
            java.util.logging.Logger otherLogger =
                java.util.logging.Logger.getLogger("some.other");

            // biz has no logger → on root
            long rootFileHandlers = Arrays.stream(root.getHandlers())
                .filter(h -> h instanceof JULFileHandler)
                .count();
            assertEquals(1, rootFileHandlers,
                "biz handler should be on root");

            // audit has logger=com.myapp.audit
            long auditFileHandlers = Arrays.stream(auditLogger.getHandlers())
                .filter(h -> h instanceof JULFileHandler)
                .count();
            assertEquals(1, auditFileHandlers,
                "audit handler should be on com.myapp.audit");

            // other logger has no JULFileHandler
            long otherFileHandlers = Arrays.stream(otherLogger.getHandlers())
                .filter(h -> h instanceof JULFileHandler)
                .count();
            assertEquals(0, otherFileHandlers,
                "other logger should have no JULFileHandler");

        } finally {
            System.clearProperty("freeway.log.file");
            System.clearProperty("freeway.log.files");
            System.clearProperty("freeway.log.file.biz.path");
            System.clearProperty("freeway.log.file.audit.path");
            System.clearProperty("freeway.log.file.audit.logger");
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void multiFileWritesToSeparateFiles(@TempDir Path tempDir) throws Exception {
        Path bizPath = tempDir.resolve("biz.log");
        Path auditPath = tempDir.resolve("audit.log");

        System.setProperty("freeway.log.file", "off"); // no default file
        System.setProperty("freeway.log.files", "biz,audit");
        System.setProperty("freeway.log.file.biz.path", bizPath.toString());
        System.setProperty("freeway.log.file.audit.path", auditPath.toString());
        System.setProperty("freeway.log.file.audit.logger", "com.myapp.audit");

        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            org.slf4j.Logger bizLogger =
                LoggerFactory.getLogger("com.myapp.biz");
            org.slf4j.Logger auditLogger =
                LoggerFactory.getLogger("com.myapp.audit");

            bizLogger.info("biz specific message");
            auditLogger.info("audit specific message");
            auditLogger.warn("audit warning");

            // Flush all JULFileHandlers
            java.util.logging.Logger root =
                java.util.logging.Logger.getLogger("");
            for (var h : root.getHandlers()) {
                if (h instanceof JULFileHandler) h.flush();
            }
            for (var h : java.util.logging.Logger
                    .getLogger("com.myapp.audit").getHandlers()) {
                if (h instanceof JULFileHandler) h.flush();
            }

            // Biz file should have biz message but NOT audit messages
            String bizContent = Files.readString(bizPath);
            assertTrue(bizContent.contains("biz specific message"),
                "biz file should contain biz message");
            assertFalse(bizContent.contains("audit specific message"),
                "biz file should NOT contain audit message");

            // Audit file should have audit messages
            String auditContent = Files.readString(auditPath);
            assertTrue(auditContent.contains("audit specific message"),
                "audit file should contain audit message");
            assertTrue(auditContent.contains("audit warning"),
                "audit file should contain audit warning");

        } finally {
            System.clearProperty("freeway.log.file");
            System.clearProperty("freeway.log.files");
            System.clearProperty("freeway.log.file.biz.path");
            System.clearProperty("freeway.log.file.audit.path");
            System.clearProperty("freeway.log.file.audit.logger");
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void multiFileMissingPathDoesNotCrash() {
        System.setProperty("freeway.log.files", "missing,also-missing");

        JULEnhancer.resetForTest();
        try {
            assertDoesNotThrow(() -> JULEnhancer.configure(),
                "Missing path should log warning, not crash");
        } finally {
            System.clearProperty("freeway.log.files");
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void defaultFilePlusNamedFiles(@TempDir Path tempDir) throws Exception {
        Path mainPath = tempDir.resolve("main.log");
        Path extraPath = tempDir.resolve("extra.log");

        // Set default file path AND named files
        System.setProperty("freeway.log.file", mainPath.toString());
        System.setProperty("freeway.log.files", "extra");
        System.setProperty("freeway.log.file.extra.path", extraPath.toString());

        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            java.util.logging.Logger root =
                java.util.logging.Logger.getLogger("");

            // Both should be on root: default + extra
            long rootFileHandlers = Arrays.stream(root.getHandlers())
                .filter(h -> h instanceof JULFileHandler)
                .count();
            assertEquals(2, rootFileHandlers,
                "Root should have 2 JULFileHandlers (default + extra)");

        } finally {
            System.clearProperty("freeway.log.file");
            System.clearProperty("freeway.log.files");
            System.clearProperty("freeway.log.file.extra.path");
            JULEnhancer.resetForTest();
        }
    }

    // ── console handler ────────────────────────────────────────

    @Test
    void consoleCanBeDisabled() {
        System.setProperty("freeway.log.console.enabled", "false");
        System.setProperty("freeway.log.file", "off");

        // Isolation: earlier tests in this JVM may have left handlers on the
        // root logger (e.g. stock ones upgraded with freeway formatters —
        // which under the ownership contract now count as customized). Start
        // from a clean root, like a fresh JVM would.
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        java.util.List<Handler> preExisting = new ArrayList<>(Arrays.asList(root.getHandlers()));
        for (Handler h : preExisting) {
            root.removeHandler(h);
        }

        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            long consoleHandlers = Arrays.stream(root.getHandlers())
                .filter(h -> h instanceof ConsoleHandler)
                .count();
            assertEquals(0, consoleHandlers,
                "stock and freeway-owned ConsoleHandlers should be removed when disabled");
        } finally {
            for (Handler h : preExisting) {
                root.addHandler(h);
            }
            System.clearProperty("freeway.log.console.enabled");
            System.clearProperty("freeway.log.file");
            JULEnhancer.resetForTest();
        }
    }

    // ── level configuration ────────────────────────────────────

    @Test
    void systemPropertyOverridesLogLevel() {
        System.setProperty("freeway.log.level", "FINE");
        System.setProperty("freeway.log.file", "off");

        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            java.util.logging.Logger root =
                java.util.logging.Logger.getLogger("");
            assertEquals(Level.FINE, root.getLevel(),
                "System property should override freeway-log.properties");
        } finally {
            System.clearProperty("freeway.log.level");
            System.clearProperty("freeway.log.file");
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void perLoggerLevelViaSystemProperty() {
        System.setProperty("freeway.log.file", "off");
        System.setProperty("com.myapp.audit.level", "FINE");

        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            java.util.logging.Logger auditLogger =
                java.util.logging.Logger.getLogger("com.myapp.audit");
            assertEquals(Level.FINE, auditLogger.getLevel(),
                "Per-logger level should be FINE");
        } finally {
            System.clearProperty("freeway.log.file");
            System.clearProperty("com.myapp.audit.level");
            JULEnhancer.resetForTest();
        }
    }

    @Test
    void fileLoggingActivatesWhenConfigured(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("logs").resolve("auto.log");
        System.setProperty("freeway.log.file", logFile.toString());
        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            boolean found = false;
            for (Handler h : root.getHandlers()) {
                if (h instanceof JULFileHandler) {
                    found = true;
                    h.close();
                }
            }
            assertTrue(found, "Root logger should have a JULFileHandler after activation");
        } finally {
            System.clearProperty("freeway.log.file");
            JULEnhancer.resetForTest();
        }
    }

    // ── parseLogLevel ───────────────────────────────────────

    @Test
    void parseLogLevelMapsSlf4jNames() {
        assertEquals(Level.FINEST, JULEnhancer.parseLogLevel("TRACE"),
            "TRACE → FINEST");
        assertEquals(Level.FINE, JULEnhancer.parseLogLevel("DEBUG"),
            "DEBUG → FINE");
        assertEquals(Level.INFO, JULEnhancer.parseLogLevel("INFO"),
            "INFO → INFO");
        assertEquals(Level.WARNING, JULEnhancer.parseLogLevel("WARN"),
            "WARN → WARNING");
        assertEquals(Level.WARNING, JULEnhancer.parseLogLevel("WARNING"),
            "WARNING → WARNING");
        assertEquals(Level.SEVERE, JULEnhancer.parseLogLevel("ERROR"),
            "ERROR → SEVERE");
        assertEquals(Level.SEVERE, JULEnhancer.parseLogLevel("FATAL"),
            "FATAL → SEVERE");
        assertEquals(Level.OFF, JULEnhancer.parseLogLevel("OFF"),
            "OFF → OFF");
        assertEquals(Level.ALL, JULEnhancer.parseLogLevel("ALL"),
            "ALL → ALL");
    }

    @Test
    void parseLogLevelAcceptsJulNames() {
        assertEquals(Level.FINEST, JULEnhancer.parseLogLevel("FINEST"));
        assertEquals(Level.FINER, JULEnhancer.parseLogLevel("FINER"));
        assertEquals(Level.FINE, JULEnhancer.parseLogLevel("FINE"));
        assertEquals(Level.SEVERE, JULEnhancer.parseLogLevel("SEVERE"));
    }

    @Test
    void parseLogLevelIsCaseInsensitive() {
        assertEquals(Level.FINE, JULEnhancer.parseLogLevel("debug"));
        assertEquals(Level.FINE, JULEnhancer.parseLogLevel("Debug"));
        assertEquals(Level.FINE, JULEnhancer.parseLogLevel("DEBUG"));
        assertEquals(Level.SEVERE, JULEnhancer.parseLogLevel("error"));
    }

    @Test
    void parseLogLevelRejectsUnknown() {
        assertThrows(IllegalArgumentException.class,
            () -> JULEnhancer.parseLogLevel("BOGUS"));
        assertThrows(IllegalArgumentException.class,
            () -> JULEnhancer.parseLogLevel(""));
    }

    @Test
    void invalidLevelDoesNotAbortConfiguration() {
        System.setProperty("freeway.log.level", "BOGUS");
        System.setProperty("freeway.log.file", "off");

        JULEnhancer.resetForTest();
        try {
            assertDoesNotThrow(() -> JULEnhancer.configure(),
                "Invalid level should not crash configure()");
            java.util.logging.Logger root =
                java.util.logging.Logger.getLogger("");
            assertNotNull(root.getLevel());
        } finally {
            System.clearProperty("freeway.log.level");
            System.clearProperty("freeway.log.file");
            JULEnhancer.resetForTest();
        }
    }

    // ── caller info ─────────────────────────────────────────

    @Test
    void sfl4jLoggerHasCorrectCallerInfo() {
        var records = new ArrayList<LogRecord>();

        java.util.logging.Logger testLogger =
            java.util.logging.Logger.getLogger("caller.test");
        testLogger.setUseParentHandlers(false);
        testLogger.setLevel(Level.ALL);
        testLogger.addHandler(new Handler() {
            { setLevel(Level.ALL); }
            @Override public void publish(LogRecord r) {
                records.add(r);
            }
            @Override public void flush() {}
            @Override public void close() {}
        });

        org.slf4j.Logger slf4j = org.slf4j.LoggerFactory.getLogger("caller.test");
        slf4j.info("test caller info");

        assertEquals(1, records.size(), "Should have captured one record");
        LogRecord record = records.getFirst();

        assertEquals("caller.test", record.getLoggerName());
        assertEquals(Level.INFO, record.getLevel());

        // Source info should be THIS test class, not JULLoggerAdapter
        assertEquals(
            LogBootstrapTest.class.getName(),
            record.getSourceClassName(),
            "source class should be the test class, not the SLF4J bridge"
        );
        assertEquals(
            "sfl4jLoggerHasCorrectCallerInfo",
            record.getSourceMethodName(),
            "source method should be the test method"
        );

        testLogger.removeHandler(testLogger.getHandlers()[0]);
    }

    @Test
    void misbindingWarningOnlyFiresForJulFallbackWithExternalPresent() {
        String logback = "ch.qos.logback.classic.spi.LogbackServiceProvider";

        assertNull(LogBootstrap.misbindingWarning(null, true),
            "no external provider detected → binding is intentional, no warning");
        assertNull(LogBootstrap.misbindingWarning(logback, false),
            "external provider actually bound → intent fulfilled, no warning");

        String warning = LogBootstrap.misbindingWarning(logback, true);
        assertNotNull(warning,
            "external on classpath but JUL bound → must warn");
        assertTrue(warning.contains(logback), "names the detected provider");
        assertTrue(warning.contains("-Dslf4j.provider=" + logback),
            "gives the actionable fix");
        assertTrue(warning.contains("before bootstrap"),
            "explains the cause (early static logger)");
    }
}
