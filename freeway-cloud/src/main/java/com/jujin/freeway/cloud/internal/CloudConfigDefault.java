package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.cloud.config.CloudConfig;
import com.jujin.freeway.cloud.config.ConfigChangedEvent;
import com.jujin.freeway.cloud.config.ConfigSubscription;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-backed {@link CloudConfig} with {@link WatchService} hot reload: a
 * daemon thread watches the file's directory for create/modify/delete events,
 * re-reads the properties file and notifies watchers of every diff.
 *
 * <p>Notifications cover removals too: a key deleted from the file (or the
 * whole file deleted) publishes a {@link ConfigChangedEvent} with a
 * {@code null} newValue; {@code watch()} listeners are value consumers and are
 * only invoked for keys that still have a value — removals signal via the
 * event only. The initial load notifies nothing.
 */
public final class CloudConfigDefault implements CloudConfig, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CloudConfigDefault.class);

    private final Path file;
    private final Consumer<ConfigChangedEvent> onChange;
    private final AtomicReference<Map<String, String>> values = new AtomicReference<>(Map.of());
    private final Map<String, List<Consumer>> listeners = new ConcurrentHashMap<>();

    private final WatchService watchService;
    private final Thread watcher;
    private volatile boolean loaded;

    public CloudConfigDefault(Path file) {
        this(file, null);
    }

    /** @param onChange optional change callback (e.g. EventBus publish); not called for the initial load */
    public CloudConfigDefault(Path file, Consumer<ConfigChangedEvent> onChange) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.onChange = onChange;
        reload();
        Path dir = this.file.getParent();
        WatchService ws = null;
        Thread thread = null;
        if (dir != null && Files.isDirectory(dir)) {
            try {
                ws = FileSystems.getDefault().newWatchService();
                dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                thread = new Thread(this::watchLoop, "cloud-config-watch-" + this.file.getFileName());
                thread.setDaemon(true);
            } catch (IOException e) {
                LOG.warn("Config watch disabled for {}: {}", this.file, e.getMessage());
                ws = null;
                thread = null;
            }
        }
        // Assign the fields BEFORE start(): watchLoop dereferences watchService
        // on its first take() and can outrun the constructor's tail otherwise.
        this.watchService = ws;
        this.watcher = thread;
        if (thread != null) {
            thread.start();
        }
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get().get(key));
    }

    @Override
    public Map<String, String> asMap() {
        return Map.copyOf(values.get());
    }

    /**
     * {@code listener} receives every non-null value change of {@code key}.
     * When the key is removed from the file (or the file is deleted) only the
     * {@link ConfigChangedEvent} (newValue {@code null}) signals it — value
     * listeners are not invoked with a null value.
     */
    @Override
    public ConfigSubscription watch(String key, Consumer<String> listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> listeners.getOrDefault(key, List.of()).remove(listener);
    }

    @Override
    public synchronized void reload() {
        Map<String, String> next;
        if (!Files.isRegularFile(file)) {
            next = Map.of(); // deleted/absent: empty config — removals are notified below
        } else {
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                LOG.warn("Failed to read config file {}: {}", file, e.getMessage());
                return; // keep the last good snapshot on a partial/unreadable file
            }
            Map<String, String> map = new java.util.HashMap<>();
            props.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
            next = Map.copyOf(map);
        }
        publishDiff(values.getAndSet(next), next);
    }

    /**
     * Notifies watchers of every key whose value changed or was removed
     * (union of both key sets, sorted for deterministic order). The initial
     * load is not a change and notifies nothing.
     */
    private void publishDiff(Map<String, String> previous, Map<String, String> snapshot) {
        if (!loaded) {
            loaded = true;
            return;
        }
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(previous.keySet());
        keys.addAll(snapshot.keySet());
        for (String key : keys) {
            String oldValue = previous.get(key);
            String newValue = snapshot.get(key);
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            Consumer<ConfigChangedEvent> cb = onChange;
            if (cb != null) {
                cb.accept(new ConfigChangedEvent(key, oldValue, newValue)); // null new = removed
            }
            if (newValue != null) {
                notify(key, newValue);
            }
        }
    }

    public void close() {
        if (watcher != null) {
            watcher.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                LOG.debug("WatchService close failed", e);
            }
        }
    }

    private void watchLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    if (ctx instanceof Path changed && changed.getFileName().equals(file.getFileName())) {
                        reload();
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("Config watch loop stopped: {}", e.getMessage());
        }
    }

    private void notify(String key, String value) {
        for (Consumer listener : listeners.getOrDefault(key, List.of())) {
            try {
                listener.accept(value);
            } catch (Exception e) {
                LOG.warn("Config listener failed for {}: {}", key, e.getMessage());
            }
        }
    }
}
