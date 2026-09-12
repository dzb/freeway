package com.jujin.freeway.boot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The override-file tier: later files win, and a key two of them declare is
 * named instead of silently losing one of the two values.
 */
class OverrideFileTierTest {

    /** The file tier's value for {@code key} — the tier the overrides feed. */
    private static String fileValue(AppConfigDefault config, String key) {
        return config.providers().stream()
            .filter(provider -> provider.order() == com.jujin.freeway.ioc.symbol.SymbolProvider.TIER_FILES)
            .findFirst()
            .orElseThrow()
            .lookup(key);
    }

    private static AppConfigDefault of(List<Path> overrideFiles) {
        return new AppConfigDefault(
            new ConfigSources(Map.of(), Map.of(), Map.of(), List.of()),
            overrideFiles);
    }

    @Test
    void laterOverrideFileWins(@TempDir Path dir) throws Exception {
        Path first = Files.writeString(dir.resolve("first.properties"), "freeway.db.url=jdbc:first\n");
        Path second = Files.writeString(dir.resolve("second.properties"), "freeway.db.url=jdbc:second\n");
        AppConfigDefault config = of(List.of(first, second));
        try {
            assertEquals("jdbc:second", fileValue(config, "freeway.db.url"));
        } finally {
            config.close();
        }
    }

    @Test
    void duplicateKeyAcrossOverrideFilesIsNamed(@TempDir Path dir) throws Exception {
        Path first = Files.writeString(dir.resolve("first.properties"), "freeway.db.url=jdbc:first\n");
        Path second = Files.writeString(dir.resolve("second.properties"), "freeway.db.url=jdbc:second\n");
        List<String> warnings = captureWarnings(() -> {
            AppConfigDefault config = of(List.of(first, second));
            config.close();
        });
        String duplicate = warnings.stream()
            .filter(w -> w.contains("freeway.db.url"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected a duplicate-key warning, got " + warnings));
        assertTrue(duplicate.contains("first.properties") && duplicate.contains("second.properties"),
            "the warning must name both files: " + duplicate);
    }

    @Test
    void distinctKeysAcrossOverrideFilesAreSilent(@TempDir Path dir) throws Exception {
        Path first = Files.writeString(dir.resolve("first.properties"), "freeway.db.url=jdbc:first\n");
        Path second = Files.writeString(dir.resolve("second.properties"), "freeway.db.username=sa\n");
        List<String> warnings = captureWarnings(() -> {
            AppConfigDefault config = of(List.of(first, second));
            config.close();
        });
        assertEquals(List.of(), warnings, "no key is declared twice");
    }

    @Test
    void classpathBaselineShadowedByAnOverrideFileIsSilent(@TempDir Path dir) throws Exception {
        // The baseline value is dead by design — externalizing a packaged
        // value is what the override tier is for, so it is not a duplicate.
        Path extra = Files.writeString(dir.resolve("extra.properties"), "freeway.db.url=jdbc:override\n");
        List<String> warnings = captureWarnings(() -> {
            AppConfigDefault config = new AppConfigDefault(
                new ConfigSources(Map.of(), Map.of(),
                    Map.of("freeway.db.url", "jdbc:baseline"), List.of()),
                List.of(extra));
            try {
                assertEquals("jdbc:override", fileValue(config, "freeway.db.url"));
            } finally {
                config.close();
            }
        });
        assertEquals(List.of(), warnings, "the baseline is not an override file");
    }

    /** The WARN messages logged under the config's logger while {@code action} runs. */
    private static List<String> captureWarnings(Runnable action) {
        Logger logger = Logger.getLogger(AppConfigDefault.class.getName());
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()
                        && record.getMessage() != null) {
                    messages.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        boolean parentHandlers = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
            logger.setUseParentHandlers(parentHandlers);
        }
        return messages;
    }
}
