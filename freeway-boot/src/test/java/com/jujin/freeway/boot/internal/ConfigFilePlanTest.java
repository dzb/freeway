package com.jujin.freeway.boot.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFilePlanTest {

    @Test
    void preloadedBaseSnapshotIsNotReadAgainForProfileExpansion(@TempDir Path dir)
            throws Exception {
        Path base = dir.resolve("application.properties");
        Files.writeString(base, "app.name=from-first-read\n");

        ConfigFilePlan.Snapshot first = ConfigFilePlan.base(dir).read();
        Files.writeString(base, "app.name=changed-before-full-read\n");

        ConfigFilePlan.Snapshot full = ConfigFilePlan.full(dir, List.of("dev"))
            .read(first.byPath());

        assertEquals("from-first-read", full.values().get("app.name"),
            "startup profile selection and the file tier share one base snapshot");
        assertEquals(6, full.files().size(),
            "the full plan carries three base files plus three profile variants");
    }

    @Test
    void profileVariantsStripTheirOwnActivationKey(@TempDir Path dir) throws Exception {
        Path variant = dir.resolve("application-dev.properties");
        Files.writeString(variant, "freeway.profile=prod\napp.name=demo\n");

        Map<String, String> values = ConfigFilePlan
            .full(dir, List.of("dev"))
            .read()
            .values();

        assertEquals("demo", values.get("app.name"));
        assertNull(values.get("freeway.profile"));
    }

    @Test
    void tomlFileInTheFilesystemTierIsParsedByExtension(@TempDir Path dir)
            throws Exception {
        Path toml = Files.writeString(dir.resolve("application.toml"),
            """
            [app]
            name = "from-toml"
            port = 8080
            """);

        Map<String, String> values = ConfigFilePlan.paths(List.of(toml)).read().values();

        assertEquals("from-toml", values.get("app.name"));
        assertEquals("8080", values.get("app.port"),
            "an integer flattens to its decimal text");
    }

    @Test
    void malformedTomlFileFailsWithTheSourceName(@TempDir Path dir) throws Exception {
        Path toml = Files.writeString(dir.resolve("application.toml"), "[app\n");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ConfigFilePlan.paths(List.of(toml)).read());

        assertTrue(ex.getMessage().contains("Unable to load application.toml"),
            "got: " + ex.getMessage());
    }
}
