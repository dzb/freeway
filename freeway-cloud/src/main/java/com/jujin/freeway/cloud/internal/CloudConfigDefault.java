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
 * re-reads the properties file and notifies per-key listeners.
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
                thread.start();
            } catch (IOException e) {
                LOG.warn("Config watch disabled for {}: {}", this.file, e.getMessage());
                ws = null;
            }
        }
        this.watchService = ws;
        this.watcher = thread;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get().get(key));
    }

    @Override
    public Map<String, String> asMap() {
        return Map.copyOf(values.get());
    }

    @Override
    public ConfigSubscription watch(String key, Consumer<String> listener) {
        listeners.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> listeners.getOrDefault(key, List.of()).remove(listener);
    }

    @Override
    public void reload() {
        if (!Files.isRegularFile(file)) {
            values.set(Map.of());
            return;
        }
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            LOG.warn("Failed to read config file {}: {}", file, e.getMessage());
            return;
        }
        Map<String, String> previous = values.get();
        Map<String, String> next = new java.util.HashMap<>();
        props.forEach((k, v) -> next.put(String.valueOf(k), String.valueOf(v)));
        Map<String, String> snapshot = Map.copyOf(next);
        values.set(snapshot);
        boolean notifyChanges = loaded;
        loaded = true;
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            String old = previous.get(entry.getKey());
            if (!Objects.equals(old, entry.getValue())) {
                if (notifyChanges) {
                    Consumer<ConfigChangedEvent> cb = onChange;
                    if (cb != null) {
                        cb.accept(new ConfigChangedEvent(entry.getKey(), old, entry.getValue()));
                    }
                    notify(entry.getKey(), entry.getValue());
                }
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
