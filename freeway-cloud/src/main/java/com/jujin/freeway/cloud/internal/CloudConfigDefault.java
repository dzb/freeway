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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <p>Change notifications are delivered asynchronously on a dedicated
 * single-thread executor, in change order: {@code reload()} swaps the snapshot
 * immediately (readers never wait on listeners) and a slow or throwing
 * listener cannot delay the file watcher or the next reload.</p>
 */
public final class CloudConfigDefault implements CloudConfig, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CloudConfigDefault.class);

    private final Path file;
    private final Consumer<ConfigChangedEvent> onChange;
    private final AtomicReference<Map<String, String>> values = new AtomicReference<>(Map.of());
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private final AtomicBoolean loaded = new AtomicBoolean();
    /** Serializes change delivery; the only thread listeners ever run on. */
    private final ExecutorService delivery = Executors.newSingleThreadExecutor(
        Thread.ofVirtual().name("cloud-config-notify-", 0).factory());

    private final WatchService watchService;
    private final Thread watcher;

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
        } else {
            // A missing parent directory disables hot reload for good — the
            // watcher never re-registers on its own. Make that visible at
            // startup instead of a config change being silently ignored.
            LOG.warn("Config watch disabled for {}: parent directory does not exist", this.file);
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
        return () -> listeners.compute(key, (k, list) -> {
            if (list == null) {
                return null;
            }
            list.remove(listener);
            return list.isEmpty() ? null : list;
        });
    }

    @Override
    public void reload() {
        Map<String, String> next = readSnapshot();
        if (next == null) {
            return; // unreadable — keep the last good snapshot
        }
        Map<String, String> previous = values.getAndSet(next);
        if (loaded.compareAndSet(false, true)) {
            return; // the initial load is not a change and notifies nothing
        }
        deliver(previous, next);
    }

    /** Reads the properties file. {@code null} signals "unreadable". */
    private Map<String, String> readSnapshot() {
        if (!Files.isRegularFile(file)) {
            return Map.of(); // deleted/absent: empty config — the diff signals removals
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            LOG.warn("Failed to read config file {}: {}", file, e.getMessage());
            return null;
        }
        Map<String, String> map = new java.util.HashMap<>();
        props.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
        return Map.copyOf(map);
    }

    /**
     * Queues the diff of {@code previous} → {@code snapshot} for delivery on
     * the notification thread. The swap above is already visible to readers;
     * only the callbacks wait for the queue.
     */
    private void deliver(Map<String, String> previous, Map<String, String> snapshot) {
        List<ConfigChangedEvent> changes = diff(previous, snapshot);
        try {
            delivery.execute(() -> changes.forEach(this::emit));
        } catch (RejectedExecutionException closed) {
            // close() raced the watch loop — the notification is moot
        }
    }

    /**
     * Every key whose value changed or was removed (union of both key sets,
     * sorted for deterministic order).
     */
    private static List<ConfigChangedEvent> diff(Map<String, String> previous, Map<String, String> snapshot) {
        List<ConfigChangedEvent> changes = new java.util.ArrayList<>();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(previous.keySet());
        keys.addAll(snapshot.keySet());
        for (String key : keys) {
            String oldValue = previous.get(key);
            String newValue = snapshot.get(key);
            if (!Objects.equals(oldValue, newValue)) {
                changes.add(new ConfigChangedEvent(key, oldValue, newValue)); // null new = removed
            }
        }
        return changes;
    }

    /** Delivers one change: the event callback, then the key listeners. */
    private void emit(ConfigChangedEvent event) {
        Consumer<ConfigChangedEvent> cb = onChange;
        if (cb != null) {
            try {
                cb.accept(event);
            } catch (Exception e) {
                LOG.warn("Config change listener failed for {}: {}", event.key(), e.getMessage());
            }
        }
        if (event.newValue() != null) {
            notify(event.key(), event.newValue());
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
        delivery.shutdown(); // let in-flight change notifications drain
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // close() interrupts — the only expected exit
            } catch (Exception terminal) {
                // WatchService closed underneath us — nothing left to watch.
                return;
            }
            try {
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    if (ctx instanceof Path changed && changed.getFileName().equals(file.getFileName())) {
                        reload();
                    }
                }
                key.reset();
            } catch (RuntimeException e) {
                // One failed event/reload must not kill the watcher: exiting
                // here would silently end hot reload for the process. Log and
                // keep watching; a closed WatchService surfaces on the next
                // take() and exits above.
                LOG.warn("Config watch iteration failed, continuing: {}", e.getMessage());
            }
        }
    }

    private void notify(String key, String value) {
        for (Consumer<String> listener : listeners.getOrDefault(key, List.of())) {
            try {
                listener.accept(value);
            } catch (Exception e) {
                LOG.warn("Config listener failed for {}: {}", key, e.getMessage());
            }
        }
    }
}
