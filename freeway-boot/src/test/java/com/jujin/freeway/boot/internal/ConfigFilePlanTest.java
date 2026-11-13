package com.jujin.freeway.boot.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
        assertEquals(4, full.files().size(),
            "the full plan carries two base files plus two profile variants");
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
}
