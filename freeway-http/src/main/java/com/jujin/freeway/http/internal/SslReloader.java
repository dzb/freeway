package com.jujin.freeway.http.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.jujin.freeway.http.engine.FreewayHttpEngine;

/**
 * Watches keystore mtime/size/digest and swaps a freshly built SSLContext into
 * the engine when certificate material changes. The SSL context builder is
 * injected so this class stays inside the engine and never reaches back into
 * the IoC assembly layer. The scheduler/stamp-injecting constructor is a test
 * seam for package-local tests; production uses the default constructor.
 * Public only so the IoC assembly layer ({@code HttpModule}) can construct
 * it — not part of the application API.
 *
 * <p>Change detection is two-layer: a {@link WatchService} on the keystore
 * parent directories triggers an event-driven {@link #check()} (debounced so
 * atomic-replace writes settle), while the scheduled poll at
 * {@code reloadInterval} remains as the fallback for filesystems where watch
 * events are unreliable (NFS, some bind mounts) — either layer alone drives
 * the same snapshot comparison, so a missed watch event only delays the
 * reload to the next poll instead of losing it.
 */
public final class SslReloader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SslReloader.class);

    private final FreewayHttpEngine engine;
    private final Path keyStorePath;
    private final Path trustStorePath; // nullable
    private final Path sniDirectory;   // nullable
    private final Duration reloadInterval;
    private final Supplier<SSLContext> contextBuilder;
    private final ScheduledExecutorService scheduler;
    private final FileStampProvider fileStampProvider;
    private volatile Map<Path, FileStamp> snapshot;
    /** Event-driven watcher; null when unsupported or never started. */
    private volatile WatchService watcher;
    private volatile Thread watcherThread;
    /** Grace period for the watcher thread to observe close() and exit. */
    private static final long WATCHER_JOIN_MILLIS = 2000;
    /**
     * Guards {@link #pendingCheck} and serializes {@link #check()} for
     * out-of-band callers. Production flow needs no contention on it: both
     * the poll and watch signals funnel through the single scheduler thread
     * (see {@link #signalChange()}). A dedicated object — never the public
     * instance — so external synchronization on this reloader cannot
     * interfere with reloads.
     */
    private final Object checkLock = new Object();
    /** Debounced reload requested by the watcher, awaiting its turn on the
     *  scheduler thread; guarded by {@link #checkLock}. */
    private ScheduledFuture<?> pendingCheck;

    public SslReloader(FreewayHttpEngine engine, Path keyStorePath,
                       Path trustStorePath, Path sniDirectory,
                       Duration reloadInterval,
                       Supplier<SSLContext> contextBuilder) {
        this(engine, keyStorePath, trustStorePath, sniDirectory,
            reloadInterval, contextBuilder,
            Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("freeway-ssl-reload").factory()),
            path -> Files.readAttributes(path, BasicFileAttributes.class));
    }

    public SslReloader(FreewayHttpEngine engine, Path keyStorePath,
                       Path trustStorePath, Path sniDirectory,
                       Duration reloadInterval, Supplier<SSLContext> contextBuilder,
                       ScheduledExecutorService scheduler,
                       FileStampProvider fileStampProvider) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.keyStorePath = Objects.requireNonNull(keyStorePath, "keyStorePath");
        this.trustStorePath = trustStorePath;
        this.sniDirectory = sniDirectory;
        this.reloadInterval = reloadInterval;
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.scheduler = scheduler;
        this.fileStampProvider = fileStampProvider;
    }

    public void start() {
        try {
            snapshot = snapshot();
        } catch (IOException e) {
            throw new IllegalStateException(
                "Cannot snapshot keystore files for reload", e);
        }
        long intervalMillis = Math.max(reloadInterval.toMillis(), 100);
        scheduler.scheduleWithFixedDelay(
            this::check, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        startWatcher();
    }

    /** Test seam: invoked directly by the engine-package reload tests. */
    public void check() {
        synchronized (checkLock) {
            try {
                Map<Path, FileStamp> current = snapshot();
                if (!current.equals(snapshot)) {
                    engine.reload(contextBuilder.get());
                    snapshot = current;
                    LOG.info("Reloaded HTTPS certificate material ({} keystore file(s))",
                        current.size());
                }
            } catch (Exception e) {
                LOG.error("HTTPS certificate reload failed — keeping previous context", e);
            }
        }
    }

    /**
     * Starts event-driven change detection on the keystore parent
     * directories. Best-effort: any failure (unsupported filesystem,
     * missing directory, security manager) only logs at debug — the
     * scheduled poll above remains the fallback, so reloads are delayed
     * rather than lost.
     */
    private void startWatcher() {
        Path keyFile = keyStorePath.toAbsolutePath().normalize();
        Path trustFile = trustStorePath == null
            ? null : trustStorePath.toAbsolutePath().normalize();
        Path sniDir = sniDirectory == null
            ? null : sniDirectory.toAbsolutePath().normalize();
        List<Path> dirs = new ArrayList<>(3);
        addParentDir(dirs, keyFile);
        addParentDir(dirs, trustFile);
        if (sniDir != null && Files.isDirectory(sniDir) && !dirs.contains(sniDir)) {
            dirs.add(sniDir);
        }
        if (dirs.isEmpty()) {
            return;
        }
        WatchService ws;
        try {
            ws = FileSystems.getDefault().newWatchService();
        } catch (IOException | SecurityException e) {
            LOG.debug("TLS watch service unavailable — polling fallback only", e);
            return;
        }
        try {
            for (Path dir : dirs) {
                dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            }
        } catch (IOException | SecurityException e) {
            LOG.debug("TLS watch registration failed — polling fallback only", e);
            try {
                ws.close();
            } catch (IOException ignored) {
            }
            return;
        }
        watcher = ws;
        watcherThread = Thread.ofPlatform()
            .daemon()
            .name("freeway-ssl-watch")
            .unstarted(() -> watchLoop(ws, keyFile, trustFile, sniDir));
        watcherThread.start();
    }

    private static void addParentDir(List<Path> dirs, Path file) {
        if (file == null) {
            return;
        }
        Path parent = file.getParent();
        if (parent != null && Files.isDirectory(parent) && !dirs.contains(parent)) {
            dirs.add(parent);
        }
    }

    /**
     * Event loop for {@link #watcher}. It translates filesystem events into
     * {@link #signalChange()} calls and does nothing else — no I/O, no
     * sleeping. All snapshot/digest/reload work runs on the single scheduler
     * thread, so checks never overlap and no interleaving has to be reasoned
     * about. The snapshot comparison inside {@code check()} stays
     * authoritative, so duplicate or stale events only cost a cheap digest
     * pass, never a spurious reload.
     */
    private void watchLoop(WatchService ws, Path keyFile, Path trustFile, Path sniDir) {
        while (watcher == ws) {
            WatchKey key;
            try {
                key = ws.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            boolean relevant = false;
            for (var event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    relevant = true;
                    break;
                }
                Object ctx = event.context();
                if (ctx instanceof Path name
                        && isWatchedFile((Path) key.watchable(), name,
                            keyFile, trustFile, sniDir)) {
                    relevant = true;
                    break;
                }
            }
            key.reset();
            if (relevant && watcher == ws) {
                signalChange();
            }
        }
    }

    /**
     * Requests one debounced reload on the scheduler thread, collapsing any
     * previously requested but not yet run one. Rescheduling (instead of a
     * sleeping watcher) coalesces bursts — editor save = CREATE+MODIFY,
     * atomic-replace = temp-write + rename — into a single digest pass, and
     * keeps the watcher thread free to keep taking events.
     */
    private void signalChange() {
        // Bounded debounce: long enough for atomic-replace writes to settle,
        // short enough to stay well under the poll interval. Only latency is
        // at stake — the snapshot comparison stays authoritative either way.
        long debounceMs = Math.min(Math.max(reloadInterval.toMillis(), 100), 1000);
        synchronized (checkLock) {
            if (pendingCheck != null) {
                pendingCheck.cancel(false);
            }
            try {
                pendingCheck = scheduler.schedule(
                    this::doScheduledCheck, debounceMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Shutting down — the task is dropped; the last poll (or a
                // post-restart check) covers the change. Never loud: shutdown
                // races are routine, not failures.
                LOG.debug("TLS reload signal dropped during shutdown", e);
                pendingCheck = null;
            }
        }
    }

    private void doScheduledCheck() {
        synchronized (checkLock) {
            pendingCheck = null;
        }
        check();
    }

    private static boolean isWatchedFile(Path dir, Path name,
                                         Path keyFile, Path trustFile, Path sniDir) {
        Path resolved = dir.resolve(name).toAbsolutePath().normalize();
        if (resolved.equals(keyFile)) {
            return true;
        }
        if (trustFile != null && resolved.equals(trustFile)) {
            return true;
        }
        if (sniDir != null && dir.toAbsolutePath().normalize().equals(sniDir)) {
            // A deleted file no longer passes isRegularFile, but its removal
            // still changes the snapshot — match deletions by suffix.
            if (!Files.exists(resolved)) {
                String n = name.toString().toLowerCase(java.util.Locale.ROOT);
                return n.endsWith(".p12") || n.endsWith(".pfx") || n.endsWith(".jks");
            }
            return isKeystoreFile(resolved);
        }
        return false;
    }

    private Map<Path, FileStamp> snapshot() throws IOException {
        Map<Path, FileStamp> files = new LinkedHashMap<>();
        files.put(keyStorePath, stamp(keyStorePath));
        if (trustStorePath != null) {
            files.put(trustStorePath, stamp(trustStorePath));
        }
        if (sniDirectory != null) {
            try (var stream = Files.list(sniDirectory)) {
                for (Path p : stream
                        .filter(SslReloader::isKeystoreFile).sorted().toList()) {
                    files.put(p, stamp(p));
                }
            }
        }
        return files;
    }

    private static boolean isKeystoreFile(Path path) {
        if (!Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".p12") || name.endsWith(".pfx") || name.endsWith(".jks");
    }

    private FileStamp stamp(Path path) throws IOException {
        BasicFileAttributes attrs = fileStampProvider.stamp(path);
        return new FileStamp(
            attrs.lastModifiedTime().toMillis(), attrs.size(), digest(path));
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n > 0) digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void close() {
        synchronized (checkLock) {
            if (pendingCheck != null) {
                pendingCheck.cancel(false);
                pendingCheck = null;
            }
        }
        WatchService ws = watcher;
        watcher = null;
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException ignored) {
                // closing wakes the watcher's blocking take() so it exits
            }
        }
        Thread t = watcherThread;
        watcherThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(WATCHER_JOIN_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        scheduler.shutdownNow();
    }

    record FileStamp(long lastModified, long size, String digest) {}

    /** Internal seam: abstracts file-stamp reads for {@link SslReloader}
     *  tests. Not part of the public API. */
    @FunctionalInterface
    public interface FileStampProvider {
        BasicFileAttributes stamp(Path path) throws IOException;
    }
}
