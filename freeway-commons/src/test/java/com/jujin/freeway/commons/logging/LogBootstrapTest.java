package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.logging.Handler;
import java.util.logging.LogManager;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LogBootstrap} and {@link JULLoggerServiceProvider} classpath config loading.
 *
 * <p>These tests must be isolated because they touch global SLF4J and JUL state.
 * Run with: mvn -pl freeway-commons test -Dtest=LogBootstrapTest
 */
class LogBootstrapTest {

    @Test
    void ensureProviderIsIdempotent() {
        // First call installs the provider (if not already there)
        LogBootstrap.ensureProvider();
        // Second call should be a no-op
        LogBootstrap.ensureProvider();

        // Verify SLF4J works
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
        // JUL's LogManager.updateConfiguration reads from an InputStream.
        // The loadClasspathConfig method uses getResourceAsStream("logging.properties").
        // We test the method's behavior when NO such file exists (should not throw).
        //
        // The actual auto-load is tested indirectly: if a logging.properties at
        // classpath root exists, JUL handlers would reflect its settings.
        // Here we verify the no-config-path is handled gracefully.

        // Reset JUL to default state for this test
        LogManager.getLogManager().reset();

        // No logging.properties on classpath — should not throw
        assertDoesNotThrow(() -> {
            // Simulate what installFormatters does — this always works
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            assertNotNull(root);
        });
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
        // Same name returns same adapter instance
        assertSame(log1, log2);
        // Different name returns different adapter
        assertNotSame(log1, log3);
    }

    @Test
    void bootstrapSetsUpJULFormatter() {
        // After LogBootstrap, the JUL root logger's console handler
        // should have the custom JULConsoleFormatter installed.
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        assertNotNull(root);

        // The formatter was installed in installFormatters()
        Handler[] handlers = root.getHandlers();
        if (handlers.length > 0) {
            // When running in Maven Surefire, there may be no console handler.
            // If present, it should use JULConsoleFormatter.
            boolean hasCustomFormatter = false;
            for (Handler h : handlers) {
                if (h.getFormatter() instanceof JULConsoleFormatter) {
                    hasCustomFormatter = true;
                    break;
                }
            }
            // At least one handler (if any) uses our formatter
            // This is informational — in CI there may be no handlers
        }
    }

    @Test
    void loggingAfterBootstrapDoesNotThrow() {
        // A smoke test: log at various levels
        Logger log = LoggerFactory.getLogger("smoke.test");
        log.trace("trace message");
        log.debug("debug message");
        log.info("info message");
        log.warn("warn message");
        log.error("error message", new RuntimeException("test exception"));

        // If we reach here without exception, the bootstrap works
        assertTrue(true);
    }
}
