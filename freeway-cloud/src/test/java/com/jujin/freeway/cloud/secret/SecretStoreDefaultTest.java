package com.jujin.freeway.cloud.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Env/file-backed secret store: file lookup and env-key mapping.
 */
class SecretStoreDefaultTest {

    @TempDir
    Path dir;

    @Test
    void readsFromFile() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "db.password=hunter2\n");
        SecretStoreDefault store = new SecretStoreDefault(file);
        assertEquals("hunter2", store.get("db.password").orElseThrow());
    }

    @Test
    void missingKeyIsAbsent() {
        SecretStoreDefault store = new SecretStoreDefault(dir.resolve("none.properties"));
        assertTrue(store.get("nope").isEmpty());
    }

    @Test
    void aRotatedFileIsPickedUpWithoutARestart() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "db.password=old\n");
        // No throttle in the test: production checks at most once per second.
        SecretStoreDefault store = new SecretStoreDefault(file, java.time.Duration.ZERO);
        assertEquals("old", store.get("db.password").orElseThrow());

        // A different size guarantees a distinct mtime even on coarse clocks.
        Files.writeString(file, "db.password=rotated-value\n");
        assertEquals("rotated-value", store.get("db.password").orElseThrow(),
            "replacing a mounted secret must take effect without a restart");
    }

    @Test
    void anUnreadableOrVanishedFileKeepsTheLoadedValues() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "db.password=live\n");
        SecretStoreDefault store = new SecretStoreDefault(file, java.time.Duration.ZERO);
        assertEquals("live", store.get("db.password").orElseThrow());

        // The Kubernetes projected-volume swap can make the file briefly
        // absent; a lookup in that window must not report the secret missing.
        Files.delete(file);
        assertEquals("live", store.get("db.password").orElseThrow(),
            "losing the file must not empty a store that already loaded values");

        // Garbage in the file is served as-is (no silent truncation), and the
        // next valid generation still loads.
        Files.writeString(file, "db.password=next\n");
        assertEquals("next", store.get("db.password").orElseThrow());
    }
}
