package com.jujin.freeway.boot.internal;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches the directories holding the externally supplied config files and
 * runs {@code reload} when one of those exact files is created, modified or
 * deleted. Events for sibling files in a watched directory are ignored.
 *
 * <p>Hot reload is best-effort: {@link #start} returns {@code null} when there
 * is nothing to watch — no override files, none of their directories exist, or
 * the platform {@link WatchService} is unavailable (the reason is logged) —
 * and the caller degrades to a static file tier. A directory created after
 * startup is not picked up: the watch set is fixed at start.
 *
 * <p>A failing reload never ends the watch loop, and {@link #close()} is
 * idempotent so the config can be closed by both the runtime hook and a failed
 * startup path.
 */
final class ConfigFileWatcher implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(
        ConfigFileWatcher.class
    );

    /** Absolute normalized paths — the files whose events trigger a reload. */
    private final Set<Path> files;
    private final Runnable reload;
    private final WatchService watchService;
    private final Thread thread;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ConfigFileWatcher(Set<Path> files, Runnable reload, WatchService watchService) {
        this.files = files;
        this.reload = reload;
        this.watchService = watchService;
        this.thread = Thread.ofPlatform()
            .daemon()
            .name("freeway-config-watch")
            .unstarted(this::watchLoop);
    }

    /**
     * Starts watching {@code files}; returns {@code null} when no directory
     * could be registered (hot reload disabled, never a failed startup).
     */
    static ConfigFileWatcher start(List<Path> files, Runnable reload) {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(reload, "reload");
        Set<Path> targets = new LinkedHashSet<>();
        for (Path file : files) {
            targets.add(file.toAbsolutePath().normalize());
        }
        if (targets.isEmpty()) {
            return null;
        }
        WatchService watchService;
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            LOG.warn("Config watch disabled: {}", e.getMessage());
            return null;
        }
        try {
            if (!register(watchService, targets)) {
                watchService.close(); // nothing to watch — do not hold the handle
                return null;
            }
        } catch (IOException e) {
            LOG.warn("Config watch disabled: {}", e.getMessage());
            closeQuietly(watchService);
            return null;
        }
        ConfigFileWatcher watcher = new ConfigFileWatcher(targets, reload, watchService);
        watcher.thread.start();
        return watcher;
    }

    /** Registers each distinct existing parent directory once. */
    private static boolean register(WatchService watchService, Set<Path> files)
        throws IOException {
        Set<Path> directories = new LinkedHashSet<>();
        for (Path file : files) {
            Path dir = file.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                directories.add(dir);
            }
        }
        for (Path dir : directories) {
            dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        }
        return !directories.isEmpty();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        thread.interrupt();
        closeQuietly(watchService);
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // close() interrupts — the only expected exit
            } catch (ClosedWatchServiceException e) {
                return; // close() raced the loop
            } catch (RuntimeException e) {
                LOG.warn("Config watch stopped: {}", e.getMessage());
                return;
            }
            try {
                if (touched(key)) {
                    reload.run();
                }
            } catch (RuntimeException e) {
                // One failed iteration must not silently end hot reload.
                LOG.warn(
                    "Config reload failed, keeping the previous snapshot: {}",
                    e.getMessage()
                );
            }
            boolean valid;
            try {
                valid = key.reset();
            } catch (RuntimeException e) {
                return; // WatchService closed underneath us
            }
            if (!valid) {
                return; // the watched directory no longer exists
            }
        }
    }

    /** Whether the event batch touched one of the watched files. */
    private boolean touched(WatchKey key) {
        boolean touched = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (!(event.context() instanceof Path changed)) {
                continue;
            }
            Path absolute = ((Path) key.watchable())
                .resolve(changed)
                .toAbsolutePath()
                .normalize();
            if (files.contains(absolute)) {
                touched = true;
            }
        }
        return touched;
    }

    private static void closeQuietly(WatchService watchService) {
        try {
            watchService.close();
        } catch (IOException | RuntimeException e) {
            LOG.debug("WatchService close failed", e);
        }
    }
}
