package com.jujin.freeway.boot.internal;

import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.Watchable;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which event batches require a reload. OVERFLOW is the case hot reload used
 * to miss: its context is null, so a "did a watched file change?" test answers
 * no while the change it describes may be the watched file's own.
 */
class ConfigFileWatcherTest {

    private static final Path DIR = Path.of("/config").toAbsolutePath();
    private static final Path WATCHED = DIR.resolve("application.properties");
    private static final Set<Path> WATCHED_FILES = Set.of(WATCHED);

    @Test
    void aWatchedFileChangeRequiresAReload() {
        assertTrue(ConfigFileWatcher.touched(
            key(event(StandardWatchEventKinds.ENTRY_MODIFY, "application.properties")),
            WATCHED_FILES));
    }

    @Test
    void aSiblingFileChangeDoesNot() {
        assertFalse(ConfigFileWatcher.touched(
            key(event(StandardWatchEventKinds.ENTRY_MODIFY, "other.properties")),
            WATCHED_FILES));
    }

    @Test
    void anOverflowForcesAReloadBecauseTheDroppedEventMayBeOurs() {
        assertTrue(ConfigFileWatcher.touched(
            key(event(StandardWatchEventKinds.OVERFLOW, null)),
            WATCHED_FILES),
            "dropped events leave the config stale for as long as the next save");
    }

    @Test
    void anEventWithNoContextThatIsNotAnOverflowIsIgnored() {
        assertFalse(ConfigFileWatcher.touched(
            key(event(StandardWatchEventKinds.ENTRY_MODIFY, null)),
            WATCHED_FILES));
    }

    // ── fakes: the decision is pure over the event batch ────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static WatchEvent<?> event(WatchEvent.Kind<?> kind, String name) {
        // ENTRY_MODIFY is a Kind<Path>, OVERFLOW a Kind<Object> — the raw
        // factory covers both without inventing a second fake.
        return new WatchEvent() {
            @Override
            public WatchEvent.Kind kind() {
                return kind;
            }

            @Override
            public int count() {
                return 1;
            }

            @Override
            public Object context() {
                return name == null ? null : Path.of(name);
            }
        };
    }

    private static WatchKey key(WatchEvent<?>... events) {
        return new WatchKey() {
            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public List<WatchEvent<?>> pollEvents() {
                return List.of(events);
            }

            @Override
            public boolean reset() {
                return true;
            }

            @Override
            public void cancel() {
            }

            @Override
            public Watchable watchable() {
                return DIR;
            }
        };
    }
}
