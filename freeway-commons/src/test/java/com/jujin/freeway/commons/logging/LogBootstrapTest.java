package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

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

            System.out.println("=== 文件日志输出 ===");
            System.out.println(fileContent);
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

    @Test
    void fileLoggingActivatesWhenConfigured(@TempDir Path tempDir) throws Exception {
        Path logFile = tempDir.resolve("logs").resolve("auto.log");
        System.setProperty("freeway.log.file", logFile.toString());
        JULEnhancer.resetForTest();
        try {
            JULEnhancer.configure();

            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            boolean found = false;
            for (java.util.logging.Handler h : root.getHandlers()) {
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
}
