package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * The boot-supplied homes ({@link LogConfigHomes}) rank below the dedicated
 * {@code freeway-log.properties} and above code defaults: preset values fill
 * first, application files next, the dedicated file on top. commons only
 * consumes the contract — the application file family and preset knowledge
 * live in boot.
 */
class JULEnhancerFileHomesTest {

    private static LogConfigHomes homes(Map<String, String> application, Map<String, String> preset) {
        return new LogConfigHomes() {
            @Override public Map<String, String> applicationValues() {
                return application;
            }
            @Override public Map<String, String> presetValues() {
                return preset;
            }
        };
    }

    @Test
    void noHomesDegeneratesToTheDedicatedFileOnly() {
        Properties merged = JULEnhancer.loadLogConfig(null);
        assertNull(merged.getProperty("freeway.log.level"),
            "without boot there are no app-file or preset values");
    }

    @Test
    void applicationValuesFlowIntoTheLogConfig() {
        Map<String, String> app = Map.of(
            "freeway.log.level", "DEBUG",
            "freeway.db.url", "jdbc:pg"); // must be filtered by the boot impl
        Properties merged = JULEnhancer.loadLogConfig(homes(app, Map.of()));
        assertEquals("DEBUG", merged.getProperty("freeway.log.level"));
        assertNull(merged.getProperty("freeway.db.url"),
            "the SPI contract guarantees only freeway.log.* keys arrive");
    }

    @Test
    void presetValuesRankBelowApplicationValues() {
        Map<String, String> preset = new LinkedHashMap<>();
        preset.put("freeway.log.file", "off");
        Map<String, String> app = new LinkedHashMap<>();
        app.put("freeway.log.file", "auto"); // an explicit app-file value wins over the preset
        Properties merged = JULEnhancer.loadLogConfig(homes(app, preset));
        assertEquals("auto", merged.getProperty("freeway.log.file"),
            "the preset fills only what the application files did not set");

        Map<String, String> app2 = new LinkedHashMap<>();
        Properties merged2 = JULEnhancer.loadLogConfig(homes(app2, preset));
        assertEquals("off", merged2.getProperty("freeway.log.file"),
            "without an app-file value the preset supplies the default");
    }

    @Test
    void dedicatedFileOutranksTheBootSuppliedHomes(@org.junit.jupiter.api.io.TempDir
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
            Map<String, String> app = Map.of("freeway.log.level", "DEBUG");
            Properties merged = JULEnhancer.loadLogConfig(homes(app, Map.of()));
            assertEquals("SEVERE", merged.getProperty("freeway.log.level"),
                "the dedicated file is the more specific declaration and wins");
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }
}
