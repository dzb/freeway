package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The boot-supplied source ({@link LogConfigSource}) ranks below the
 * dedicated {@code freeway-log.properties} and above code defaults. commons
 * only consumes the contract — the application file family, preset knowledge
 * and their relative precedence live in boot.
 */
class JULEnhancerLogSourceTest {

    private static LogConfigSource source(Map<String, String> values) {
        return () -> values;
    }

    @Test
    void noSourceDegeneratesToTheDedicatedFileOnly() {
        Properties merged = JULEnhancer.loadLogConfig(null);
        assertNull(merged.getProperty("freeway.log.level"),
            "without boot there are no app-side values");
    }

    @Test
    void sourceValuesFlowIntoTheLogConfig() {
        Map<String, String> values = Map.of(
            "freeway.log.level", "DEBUG",
            "freeway.db.url", "jdbc:pg"); // must be filtered by the boot impl
        Properties merged = JULEnhancer.loadLogConfig(source(values));
        assertEquals("DEBUG", merged.getProperty("freeway.log.level"));
        assertNull(merged.getProperty("freeway.db.url"),
            "the SPI contract guarantees only freeway.log.* keys arrive");
    }

    @Test
    void dedicatedFileOutranksTheBootSuppliedSource(@org.junit.jupiter.api.io.TempDir
                                                    java.nio.file.Path tempDir) throws Exception {
        // The dedicated file lives on the classpath: provide one via the
        // thread-context classloader (openStream searches the TCCL first).
        java.nio.file.Files.writeString(
            tempDir.resolve("freeway-log.properties"), "freeway.log.level=SEVERE\n");
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        ClassLoader temp = new java.net.URLClassLoader(
            new java.net.URL[]{tempDir.toUri().toURL()}, saved);
        Thread.currentThread().setContextClassLoader(temp);
        try {
            Map<String, String> values = Map.of("freeway.log.level", "DEBUG");
            Properties merged = JULEnhancer.loadLogConfig(source(values));
            assertEquals("SEVERE", merged.getProperty("freeway.log.level"),
                "the dedicated file is the more specific declaration and wins");
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }
}
