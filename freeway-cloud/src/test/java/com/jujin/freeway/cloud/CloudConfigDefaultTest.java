package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.config.ConfigSubscription;
import com.jujin.freeway.cloud.internal.CloudConfigDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * File-backed config with WatchService hot reload and per-key notifications.
 */
class CloudConfigDefaultTest {

    @TempDir
    Path dir;

    @Test
    void hotReloadsOnFileChange() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "greeting=hello\n");
        try (CloudConfigDefault config = new CloudConfigDefault(file)) {
            assertEquals("hello", config.get("greeting").orElseThrow());
            Files.writeString(file, "greeting=world\n");
            await(() -> "world".equals(config.get("greeting").orElse(null)));
        }
    }

    @Test
    void watchNotifiesOnValueChange() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "k=v1\n");
        try (CloudConfigDefault config = new CloudConfigDefault(file)) {
            AtomicReference<String> seen = new AtomicReference<>();
            try (ConfigSubscription sub = config.watch("k", seen::set)) {
                Files.writeString(file, "k=v2\n");
                await(() -> "v2".equals(seen.get()));
            }
        }
    }

    @Test
    void missingFileYieldsEmptyConfig() {
        try (CloudConfigDefault config = new CloudConfigDefault(dir.resolve("absent.properties"))) {
            assertEquals(0, config.asMap().size());
        }
    }

    @Test
    void onChangeCallbackReceivesOldAndNewValue() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "k=v1\n");
        java.util.List<com.jujin.freeway.cloud.config.ConfigChangedEvent> events =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        try (CloudConfigDefault config = new CloudConfigDefault(file, events::add)) {
            Files.writeString(file, "k=v2\n");
            await(() -> !events.isEmpty());
            assertEquals("k", events.get(0).key());
            assertEquals("v1", events.get(0).oldValue());
            assertEquals("v2", events.get(0).newValue());
        }
    }

    @Test
    void fileDeletionNotifiesRemovals() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "k=v1\n");
        java.util.List<com.jujin.freeway.cloud.config.ConfigChangedEvent> events =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        try (CloudConfigDefault config = new CloudConfigDefault(file, events::add)) {
            AtomicReference<String> seen = new AtomicReference<>("v1");
            try (ConfigSubscription sub = config.watch("k", seen::set)) {
                Files.delete(file);
                await(() -> !events.isEmpty() && events.get(0).newValue() == null);
                assertEquals("k", events.get(0).key(), "deletion must publish a removal event");
                assertEquals("v1", events.get(0).oldValue());
                assertNull(events.get(0).newValue());
                assertEquals(0, config.asMap().size(), "deleted file yields an empty snapshot");
                assertEquals("v1", seen.get(),
                    "value listeners are not invoked with a null removal — the event signals it");
            }
        }
    }

    @Test
    void keysRemovedByRewriteAreDiffed() throws Exception {
        Path file = dir.resolve("app.properties");
        Files.writeString(file, "a=1\nb=2\n");
        java.util.List<com.jujin.freeway.cloud.config.ConfigChangedEvent> events =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        try (CloudConfigDefault config = new CloudConfigDefault(file, events::add)) {
            Files.writeString(file, "b=2\n");
            await(() -> !events.isEmpty());
            assertEquals("a", events.get(0).key(), "a key removed by a rewrite must be diffed");
            assertEquals("1", events.get(0).oldValue());
            assertNull(events.get(0).newValue());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("condition not met within timeout");
    }
}
