package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot-supplied source ({@link LogConfigSource}) ranks below the
 * dedicated {@code freeway-logging.properties} and above code defaults. commons
 * only consumes the contract — the application file family and its
 * precedence live in boot.
 */
class JULEnhancerLogSourceTest {

    private static LogConfigSource source(Map<String, String> values) {
        return () -> values;
    }

    /**
     * Runs {@code body} with {@code dir} prepended to the classpath — where
     * {@link JULEnhancer} looks for the dedicated config file.
     */
    private static <T> T withClasspath(Path dir, Supplier<T> body) throws Exception {
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        ClassLoader temp = new URLClassLoader(new URL[]{dir.toUri().toURL()}, saved);
        Thread.currentThread().setContextClassLoader(temp);
        try {
            return body.get();
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
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
    void dedicatedFileOutranksTheBootSuppliedSource(@TempDir Path tempDir) throws Exception {
        Files.writeString(
            tempDir.resolve("freeway-logging.properties"), "freeway.log.level=SEVERE\n");
        Properties merged = withClasspath(tempDir,
            () -> JULEnhancer.loadLogConfig(source(Map.of("freeway.log.level", "DEBUG"))));
        assertEquals("SEVERE", merged.getProperty("freeway.log.level"),
            "the dedicated file is the more specific declaration and wins");
    }

    @Test
    void renamedFileNameIsNotRead(@TempDir Path tempDir) throws Exception {
        // The pre-1.5.2 name is dead: keep it from being a second live name
        // (two names for one configuration is two answers to one question).
        // The classpath notice, not a fallback, is what an upgrade gets.
        Files.writeString(
            tempDir.resolve("freeway-log.properties"), "freeway.log.level=WARNING\n");
        Properties merged = withClasspath(tempDir, () -> JULEnhancer.loadLogConfig(null));
        assertNull(merged.getProperty("freeway.log.level"),
            "a file under the renamed name must not be loaded");
    }

    @Test
    void renamedFileNameProducesTheRenameNotice(@TempDir Path tempDir) throws Exception {
        Files.writeString(
            tempDir.resolve("freeway-log.properties"), "freeway.log.level=WARNING\n");
        String notice = withClasspath(tempDir, JULEnhancer::renamedFileNotice);
        assertNotNull(notice, "a leftover renamed file must be reported at startup");
        assertTrue(notice.contains("freeway-logging.properties"),
            "the notice names the file to use instead: " + notice);
    }

    @Test
    void theNoticeDoesNotDependOnTheCanonicalFileBeingAbsent(@TempDir Path tempDir)
        throws Exception {
        // The stale file is dead either way; reporting it only when the
        // canonical one is missing would leave a live-looking config file that
        // silently does nothing for whoever edits it next.
        Files.writeString(
            tempDir.resolve("freeway-logging.properties"), "freeway.log.level=SEVERE\n");
        Files.writeString(
            tempDir.resolve("freeway-log.properties"), "freeway.log.level=WARNING\n");
        String notice = withClasspath(tempDir, JULEnhancer::renamedFileNotice);
        assertNotNull(notice,
            "the renamed file is reported even when the canonical one carries the values");
    }

    @Test
    void canonicalFileAppliesRegardlessOfTheRenamedOne(@TempDir Path tempDir) throws Exception {
        Files.writeString(
            tempDir.resolve("freeway-logging.properties"), "freeway.log.level=SEVERE\n");
        Files.writeString(
            tempDir.resolve("freeway-log.properties"), "freeway.log.level=WARNING\n");
        Properties merged = withClasspath(tempDir, () -> JULEnhancer.loadLogConfig(null));
        assertEquals("SEVERE", merged.getProperty("freeway.log.level"),
            "only the canonical name carries values");
    }
}
