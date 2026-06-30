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

        // logging.properties sets .level=INFO for the root logger
        assertEquals(Level.INFO, root.getLevel(),
            "Root logger level should be INFO per logging.properties");

        // installFormatters() sets JULConsoleFormatter on ConsoleHandler
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
}
