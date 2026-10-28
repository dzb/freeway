package com.jujin.freeway.cloud.rpc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The rotating material's own rules: a zero interval keeps the resolved-once
 * shape, a positive one re-reads once the stamp changes, and a file that cannot
 * be read keeps the material that worked — a rotation in progress must not take
 * the process down.
 */
class RotatingTlsMaterialTest {

    private static final String PASSWORD = "changeit";

    private static Path keyStore(Path dir, String name, String dname) throws Exception {
        Path store = dir.resolve(name);
        Process keytool = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
            "-genkeypair", "-keyalg", "RSA", "-keysize", "2048",
            "-dname", dname, "-validity", "365",
            "-alias", "tls", "-storetype", "PKCS12",
            "-keystore", store.toString(), "-storepass", PASSWORD)
            .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertTrue(keytool.waitFor() == 0, "keytool must create " + store);
        return store;
    }

    @Test
    void a_zero_interval_never_reloads(@TempDir Path dir) throws Exception {
        Path store = keyStore(dir, "a.p12", "CN=first");
        RotatingTlsMaterial material = new RotatingTlsMaterial(
            store, PASSWORD, null, null, Duration.ZERO);

        RotatingTlsMaterial.Material before = material.current();
        Files.writeString(store.resolveSibling("unrelated"), "x"); // no stamp change
        assertSame(before, material.current(), "a zero interval keeps one material");

        // Replacing the file does not matter either: the interval is what
        // enables rotation at all.
        Files.delete(store);
        keyStore(dir, "a.p12", "CN=second");
        assertSame(before, material.current(), "rotation is off, so nothing is re-read");
    }

    @Test
    void a_rotated_file_is_picked_up_after_the_interval(@TempDir Path dir) throws Exception {
        Path store = keyStore(dir, "a.p12", "CN=first");
        RotatingTlsMaterial material = new RotatingTlsMaterial(
            store, PASSWORD, null, null, Duration.ofMillis(50));
        String firstStamp = material.current().stamp();

        Thread.sleep(60); // let the throttle window pass
        store.toFile().delete();
        keyStore(dir, "a.p12", "CN=second");

        RotatingTlsMaterial.Material reloaded = material.current();
        assertNotEquals(firstStamp, reloaded.stamp(),
            "a changed file must produce new material");
        assertTrue(reloaded.keyManagers().length > 0);
    }

    @Test
    void unreadable_material_keeps_the_previous_one(@TempDir Path dir) throws Exception {
        Path store = keyStore(dir, "a.p12", "CN=first");
        RotatingTlsMaterial material = new RotatingTlsMaterial(
            store, PASSWORD, null, null, Duration.ofMillis(50));
        RotatingTlsMaterial.Material good = material.current();

        Thread.sleep(60);
        Files.writeString(store, "not a keystore"); // half-written / mid-replace

        RotatingTlsMaterial.Material afterFailure = material.current();
        assertSame(good, afterFailure,
            "an unreadable file must not swap out the material that worked");
    }

    @Test
    void a_bad_path_fails_at_construction(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> new RotatingTlsMaterial(
            dir.resolve("missing.p12"), PASSWORD, null, null, Duration.ofMillis(50)),
            "a misconfigured path must fail at startup, not on the first call");
    }
}
