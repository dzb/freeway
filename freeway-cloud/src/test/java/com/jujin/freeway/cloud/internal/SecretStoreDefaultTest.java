package com.jujin.freeway.cloud.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.jujin.freeway.cloud.secret.SecretStoreDefault;
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
}
