package com.jujin.freeway.boot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Assumptions;

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

    @Test
    void profileVariantCannotRedeclareTheActivationKey(@TempDir Path dir) throws Exception {
        // A variant's file name IS the selection. If its own freeway.profile
        // surfaced in the file tier it would outrank everything and the
        // resolved value would contradict profiles() — the one thing AppConfig
        // promises cannot happen.
        Path variant = Files.writeString(
            dir.resolve("application-dev.properties"),
            "freeway.profile=prod\napp.name=demo\n");
        AppConfigDefault config = new AppConfigDefault(
            new ConfigSources(Map.of(), Map.of(), Map.of(), List.of("dev")),
            new AppConfigDefault.OverrideFiles(List.of(
                AppConfigDefault.OverrideFile.variant(variant))));
        try {
            assertNull(fileValue(config, "freeway.profile"),
                "the variant's own activation key must not surface");
            assertEquals("demo", fileValue(config, "app.name"),
                "every other key in the variant still applies");
            assertEquals(List.of("dev"), config.profiles());
        } finally {
            config.close();
        }
    }

    @Test
    void baseFileKeepsItsActivationKey(@TempDir Path dir) throws Exception {
        // The mirror image: a base file participates in selection, so its
        // freeway.profile is a value the app may read.
        Path base = Files.writeString(
            dir.resolve("application.properties"), "freeway.profile=dev\n");
        AppConfigDefault config = of(List.of(base));
        try {
            assertEquals("dev", fileValue(config, "freeway.profile"));
        } finally {
            config.close();
        }
    }

    @Test
    void unreadableOverrideFileFailsInsteadOfDroppingItsKeys(@TempDir Path dir) throws Exception {
        Assumptions.assumeFalse("root".equals(System.getProperty("user.name")),
            "root reads a file with no permissions");
        Path file = Files.writeString(
            dir.resolve("application.properties"), "app.name=demo\n");
        Files.setPosixFilePermissions(file, Set.of());
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> of(List.of(file)));
        assertTrue(ex.getMessage().contains("Unable to load"), ex.getMessage());
        assertTrue(ex.getMessage().contains(file.getFileName().toString()), ex.getMessage());
    }

    @Test
    void declaredFileThatDoesNotExistIsNamed(@TempDir Path dir) {
        Path missing = dir.resolve("declared-but-absent.properties");
        List<String> warnings = captureWarnings(() -> {
            AppConfigDefault config = new AppConfigDefault(
                new ConfigSources(Map.of(), Map.of(), Map.of(), List.of()),
                new AppConfigDefault.OverrideFiles(List.of(
                    AppConfigDefault.OverrideFile.declared(missing))));
            config.close();
        });
        assertTrue(
            warnings.stream().anyMatch(w -> w.contains("declared-but-absent.properties")),
            "a file named by freeway.config.file that is not there must be named: " + warnings);
    }
}
