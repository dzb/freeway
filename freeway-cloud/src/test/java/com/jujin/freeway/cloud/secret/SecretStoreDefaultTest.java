package com.jujin.freeway.cloud.secret;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("hunter2", store.find("db.password").orElseThrow());
    }

    @Test
    void missingKeyIsAbsent() {
        SecretStoreDefault store = new SecretStoreDefault(dir.resolve("none.properties"));
        assertTrue(store.find("nope").isEmpty());
        assertEquals("file-missing", store.lastSkippedRotation(),
            "a store whose file never existed reports why it is empty");
    }

    @Test
    void aRotatedFileIsPickedUpWithoutARestart() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "db.password=old\n");
        // No throttle in the test: production checks at most once per second.
        SecretStoreDefault store = new SecretStoreDefault(file, java.time.Duration.ZERO);
        assertEquals("old", store.find("db.password").orElseThrow());

        // A different size guarantees a distinct mtime even on coarse clocks.
        Files.writeString(file, "db.password=rotated-value\n");
        assertEquals("rotated-value", store.find("db.password").orElseThrow(),
            "replacing a mounted secret must take effect without a restart");
        assertNull(store.lastSkippedRotation(),
            "a clean rotation clears the previous failure reason");
    }

    @Test
    void anUnreadableOrVanishedFileKeepsTheLoadedValues() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "db.password=live\n");
        SecretStoreDefault store = new SecretStoreDefault(file, java.time.Duration.ZERO);
        assertEquals("live", store.find("db.password").orElseThrow());

        // The Kubernetes projected-volume swap can make the file briefly
        // absent; a lookup in that window must not report the secret missing.
        Files.delete(file);
        assertEquals("live", store.find("db.password").orElseThrow(),
            "losing the file must not empty a store that already loaded values");
        assertEquals("file-missing", store.lastSkippedRotation(),
            "the skipped rotation names its reason");

        // Garbage in the file is served as-is (no silent truncation), and the
        // next valid generation still loads.
        Files.writeString(file, "db.password=next\n");
        assertEquals("next", store.find("db.password").orElseThrow());
        assertNull(store.lastSkippedRotation());
    }

    @Test
    void aRotationThatParsesToNothingKeepsTheLoadedValues() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "db.password=live\napi.token=live-token\n");
        SecretStoreDefault store = new SecretStoreDefault(file, java.time.Duration.ZERO);
        assertEquals("live", store.find("db.password").orElseThrow());

        // A projected volume can expose the new generation empty, and
        // Properties drops unparsable lines without a word — parsed-to-zero is
        // the only signal that this rewrite carried no secrets.
        Files.writeString(file, "# only comments\n");
        assertEquals("live", store.find("db.password").orElseThrow(),
            "a rotation that parsed nothing must not wipe the loaded generation");
        assertEquals("live-token", store.find("api.token").orElseThrow());
        assertEquals("empty-file", store.lastSkippedRotation());

        // A real replacement afterwards still takes effect.
        Files.writeString(file, "db.password=next\napi.token=next-token\n");
        assertEquals("next", store.find("db.password").orElseThrow());
        assertEquals("next-token", store.find("api.token").orElseThrow());
        assertNull(store.lastSkippedRotation());
    }

    @Test
    void anEmptyFileAtStartupIsSimplyEmpty() throws Exception {
        Path file = dir.resolve("secrets.properties");
        Files.writeString(file, "");
        SecretStoreDefault store = new SecretStoreDefault(file);
        assertTrue(store.find("db.password").isEmpty());
        assertNull(store.lastSkippedRotation(),
            "an empty file at startup is a valid empty generation, not a skipped rotation");
    }
}
