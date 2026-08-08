package com.jujin.freeway.commons.logging;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.StreamHandler;
import java.util.zip.GZIPOutputStream;

/**
 * A JUL {@link Handler} that writes to rotating log files with industry-standard
 * time + size dual rotation and optional GZIP compression.
 *
 * <h3>Rotation</h3>
 * The <b>current</b> log file lives at the configured path (e.g.
 * {@code logs/app.log}). When rotation triggers, it is renamed to a
 * date-stamped archive name and a fresh file takes its place:
 * <pre>{@code
 * logs/app.log                          ← current
 * logs/app.2026-06-30.log              ← previous day (rotated)
 * logs/app.2026-06-30.log.gz           ← compressed after rotation
 * logs/app.2026-06-29.1.log.gz         ← size-rotated within the same day
 * }</pre>
 *
 * <h3>Triggers</h3>
 * <ul>
 *   <li><b>Day boundary</b> — the first write of a new day rotates
 *       yesterday's file.</li>
 *   <li><b>Size threshold</b> — when the current file exceeds
 *       {@code maxSize}, it is rotated with an incrementing index.</li>
 * </ul>
 *
 * <h3>Configuration</h3>
 * <pre>{@code
 * # System properties (also settable via -D flags):
 * freeway.log.file=logs/app.log
 * freeway.log.file.max-size=104857600   # 100 MB
 * freeway.log.file.max-history=30       # days
 * freeway.log.file.compress=true
 * freeway.log.file.flush-interval=250   # ms between background flushes; 0 = flush per record
 * }</pre>
 *
 * <p>All settings have sensible defaults. File logging is auto-activated
 * at startup; use {@code -Dfreeway.log.file=off} to disable.
 *
 * <p>Built entirely on JDK APIs — no external dependencies.
 */
public final class JULFileHandler extends StreamHandler {

    static final long DEFAULT_MAX_SIZE = 100L * 1024 * 1024; // 100 MB
    static final int DEFAULT_MAX_HISTORY = 30; // days
    static final boolean DEFAULT_COMPRESS = true;

    /**
     * Default interval between background flushes (milliseconds). Log records
     * are buffered and flushed together at this cadence instead of flushing
     * after every record, which avoids one write syscall per log line.
     * Set {@code freeway.log.file.flush-interval=0} to flush after every
     * record (maximum durability, lower throughput).
     */
    static final long DEFAULT_FLUSH_INTERVAL_MS = 250;

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Daemon thread for async GZIP compression so rotation never blocks logging. */
    private static final ExecutorService COMPRESSOR =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "freeway-log-compressor");
            t.setDaemon(true);
            return t;
        });

    private final Path basePath;
    private final long maxSize;
    private final int maxHistory;
    private final boolean compress;
    private final long flushIntervalMs;

    private LocalDate currentLocalDate;
    private long nextMidnightMillis;
    private int currentIndex;
    private long bytesWritten;

    /**
     * True while the underlying stream is closed because a rotation failed
     * to reopen it. publish() keeps retrying while set, so the failure stays
     * visible (the default ErrorManager prints the first error per handler)
     * and the pool recovers automatically; records published during the
     * outage are not written.
     */
    private boolean openFailed;

    /** The raw stream currently handed to StreamHandler; closed directly on rotation. */
    private OutputStream currentStream;

    /** Daemon thread performing periodic flushes; null when {@code flushIntervalMs <= 0}. */
    private final Thread flusher;
    private volatile boolean closed;

    /**
     * No-arg constructor for {@code logging.properties} / {@code LogManager}
     * instantiation. Reads configuration from system properties.
     */
    public JULFileHandler() throws IOException {
        this(
            requiredProperty("freeway.log.file"),
            LogConfig.propertyValue(
                "freeway.log.file.max-size",
                DEFAULT_MAX_SIZE,
                System::getProperty,
                Long::parseLong,
                false
            ),
            LogConfig.propertyValue(
                "freeway.log.file.max-history",
                DEFAULT_MAX_HISTORY,
                System::getProperty,
                Integer::parseInt,
                false
            ),
            LogConfig.propertyValue(
                "freeway.log.file.compress",
                DEFAULT_COMPRESS,
                System::getProperty,
                LogConfig::strictBoolean,
                false
            ),
            LogConfig.propertyValue(
                "freeway.log.file.flush-interval",
                DEFAULT_FLUSH_INTERVAL_MS,
                System::getProperty,
                Long::parseLong,
                false
            )
        );
    }

    /**
     * Programmatic constructor using the default flush interval
     * ({@link #DEFAULT_FLUSH_INTERVAL_MS}).
     */
    public JULFileHandler(
        String filePath,
        long maxSize,
        int maxHistory,
        boolean compress
    ) throws IOException {
        this(filePath, maxSize, maxHistory, compress, DEFAULT_FLUSH_INTERVAL_MS);
    }

    /**
     * Programmatic constructor.
     *
     * @param filePath        path to the log file (e.g. {@code logs/app.log})
     * @param maxSize         max bytes before size-based rotation
     * @param maxHistory      days to retain
     * @param compress        whether to gzip rotated files
     * @param flushIntervalMs background flush cadence in milliseconds;
     *                        {@code <= 0} flushes after every record
     */
    public JULFileHandler(
        String filePath,
        long maxSize,
        int maxHistory,
        boolean compress,
        long flushIntervalMs
    ) throws IOException {
        this.basePath = Paths.get(filePath).toAbsolutePath();
        this.maxSize = Math.max(1024, maxSize);
        this.maxHistory = Math.max(1, maxHistory);
        this.compress = compress;
        this.flushIntervalMs = flushIntervalMs;
        this.currentLocalDate = LocalDate.now();
        this.nextMidnightMillis = computeNextMidnight();
        this.currentIndex = 0;
        this.bytesWritten = 0;

        Files.createDirectories(basePath.getParent());
        setFormatter(new JULFileFormatter());
        setLevel(Level.ALL);
        rotateStaleFileOnStartup();
        openCurrentFile();
        this.flusher = startFlusher();
    }

    /** If the log file exists and was last modified before today, archive it. */
    private void rotateStaleFileOnStartup() {
        if (Files.exists(basePath) && fileSize(basePath) > 0) {
            try {
                LocalDate fileDate = LocalDate.ofInstant(
                    Files.getLastModifiedTime(basePath).toInstant(),
                    java.time.ZoneId.systemDefault()
                );
                if (fileDate.isBefore(currentLocalDate)) {
                    Path archived = archivedPath(DATE_FMT.format(fileDate), 0);
                    Files.move(
                        basePath,
                        archived,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                    if (compress) {
                        COMPRESSOR.execute(() -> compressFile(archived));
                    }
                }
            } catch (IOException e) {
                // best-effort — open the existing file if rotation fails
            }
        }
        // Enforce the retention window even when nothing was rotated:
        // daily-restart workloads would otherwise accumulate archives forever.
        // purgeOldFiles() is idempotent and safe to call here (parent dir is
        // guaranteed to exist by the constructor).
        purgeOldFiles();
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    // ── property helpers ─────────────────────────────────────────────

    private static String requiredProperty(String key) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException(
                key + " is required to activate JULFileHandler"
            );
        }
        return val;
    }

    // ── publish ─────────────────────────────────────────────────────

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) return;

        try {
            if (openFailed) {
                // A previous rotation closed the stream and failed to reopen
                // it; keep retrying until the failure clears so records
                // resume automatically instead of being silently dropped.
                if (!reopenAfterFailure()) {
                    return; // error already reported loudly
                }
            } else if (needsRotation()) {
                rotate();
            }
        } catch (IOException e) {
            // rotate() closed the current stream before throwing, so every
            // subsequent record would be silently discarded by the closed
            // StreamHandler. Attempt one reopen right away.
            reportError("Rotation failed", e, ErrorManager.WRITE_FAILURE);
            if (!reopenAfterFailure()) {
                return; // error already reported loudly
            }
        }

        super.publish(record);
        bytesWritten += estimateSize(record);
        if (flushIntervalMs <= 0) {
            flush(); // eager mode — maximum durability
        }
    }

    /**
     * Reopens the current log file after a rotation failure. Sets
     * {@link #openFailed} on failure so subsequent publishes keep retrying
     * and reporting rather than silently dropping records.
     *
     * @return true if the stream is usable again, false otherwise
     */
    private boolean reopenAfterFailure() {
        try {
            openCurrentFile();
            openFailed = false;
            return true;
        } catch (IOException e) {
            openFailed = true;
            reportError(
                "Failed to reopen log file",
                e,
                ErrorManager.WRITE_FAILURE
            );
            return false;
        }
    }

    /**
     * Stops the periodic flusher and closes the underlying stream.
     * Pending buffered records are flushed by {@link StreamHandler#close()}.
     */
    @Override
    public synchronized void close() {
        closed = true;
        if (flusher != null) {
            flusher.interrupt();
        }
        super.close();
    }

    private Thread startFlusher() {
        if (flushIntervalMs <= 0) {
            return null;
        }
        Thread t = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(flushIntervalMs);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    flush();
                } catch (Exception ignored) {
                    // best-effort periodic flush; errors surface on next publish
                }
            }
        }, "freeway-log-flusher-" + basePath.getFileName());
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ── rotation ────────────────────────────────────────────────────

    private boolean needsRotation() {
        if (bytesWritten >= maxSize) return true;
        return System.currentTimeMillis() >= nextMidnightMillis;
    }

    private void rotate() throws IOException {
        LocalDate today = LocalDate.now();
        boolean dayChanged = !today.equals(currentLocalDate);

        // Close current stream (best-effort — must not prevent reopening)
        try {
            closeOutputStream();
        } catch (Exception e) {
            reportError(
                "Failed to close current log stream",
                e,
                ErrorManager.WRITE_FAILURE
            );
        }

        // Archive the current file (best-effort)
        try {
            if (Files.exists(basePath) && Files.size(basePath) > 0) {
                Path archived = archivedPath(
                    DATE_FMT.format(currentLocalDate),
                    currentIndex
                );
                Files.move(
                    basePath,
                    archived,
                    StandardCopyOption.REPLACE_EXISTING
                );
                if (compress) {
                    COMPRESSOR.execute(() -> compressFile(archived));
                }
            }
        } catch (IOException e) {
            reportError(
                "Failed to archive log file",
                e,
                ErrorManager.WRITE_FAILURE
            );
        }

        // Advance counters
        if (dayChanged) {
            currentLocalDate = today;
            nextMidnightMillis = computeNextMidnight();
            currentIndex = 0;
        } else {
            currentIndex++;
        }

        bytesWritten = 0;
        openCurrentFile();
        purgeOldFiles();
    }

    // ── file operations ─────────────────────────────────────────────

    /** Builds the archived file name: {@code app.2026-06-30.log} or {@code app.2026-06-30.1.log}. */
    private Path archivedPath(String date, int index) {
        String stem = stripExtension(basePath.getFileName().toString());
        String suffix = index > 0 ? "." + index : "";
        String fileName = stem + "." + date + suffix + ".log";
        return basePath.getParent().resolve(fileName);
    }

    private void openCurrentFile() throws IOException {
        Files.createDirectories(basePath.getParent());
        OutputStream out = new BufferedOutputStream(
            new FileOutputStream(basePath.toFile(), true)
        );
        currentStream = out;
        setOutputStream(out);
        bytesWritten = fileSize(basePath);
    }

    private void closeOutputStream() {
        // Flush the StreamHandler writer FIRST — it holds records published
        // since the last background flush. Flushing after the stream is
        // closed (as setOutputStream's flushAndClose would do) hits the
        // closed FileOutputStream and drops them. Then close ONLY the raw
        // stream: StreamHandler.close() would null the internal writer and
        // make isLoggable() return false forever, defeating rotation
        // recovery.
        try {
            flush();
        } catch (Exception ignored) {
            // best-effort; a failing flush surfaces on the next publish
        }
        OutputStream out = currentStream;
        currentStream = null;
        if (out != null) {
            try {
                out.flush();
            } catch (IOException ignored) {
                // best-effort; errors surface on the next publish
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * GZIPs {@code file} into {@code <file>.gz} atomically: the archive is
     * written to a {@code .gz.tmp} staging path first and moved into place
     * only once fully written, so a crash mid-write can never leave a
     * truncated {@code .gz} that looks valid. The staging file is removed
     * on any failure.
     */
    private void compressFile(Path file) {
        Path gzFile = file.getParent().resolve(file.getFileName() + ".gz");
        Path tmpFile = file.getParent().resolve(file.getFileName() + ".gz.tmp");
        try (
            FileInputStream fin = new FileInputStream(file.toFile());
            FileOutputStream fos = new FileOutputStream(tmpFile.toFile());
            OutputStream gout = new GZIPOutputStream(fos)
        ) {
            fin.transferTo(gout);
            // closing GZIPOutputStream writes the gzip trailer, so the staging
            // file is complete by the time we exit this block
        } catch (IOException e) {
            deleteQuietly(tmpFile);
            reportError(
                "Failed to compress " + file.getFileName(),
                e,
                ErrorManager.GENERIC_FAILURE
            );
            return;
        }
        try {
            Files.move(tmpFile, gzFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteQuietly(tmpFile);
            reportError(
                "Failed to move compressed " + file.getFileName() + " into place",
                e,
                ErrorManager.GENERIC_FAILURE
            );
            return;
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            reportError(
                "Failed to delete uncompressed " + file.getFileName(),
                e,
                ErrorManager.GENERIC_FAILURE
            );
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best-effort cleanup of the staging file
        }
    }

    private void purgeOldFiles() {
        String stem = stripExtension(basePath.getFileName().toString());
        int dateStart = stem.length() + 1; // after "stem."
        LocalDate cutoff = LocalDate.now().minusDays(maxHistory);
        try (var paths = Files.list(basePath.getParent())) {
            paths
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return (
                        name.startsWith(stem + ".") &&
                        !name.equals(basePath.getFileName().toString())
                    );
                })
                .forEach(p -> {
                    String name = p.getFileName().toString();
                    if (name.length() >= dateStart + 10) {
                        try {
                            LocalDate fileDate = LocalDate.parse(
                                name.substring(dateStart, dateStart + 10),
                                DATE_FMT
                            );
                            if (fileDate.isBefore(cutoff)) {
                                Files.delete(p);
                            }
                        } catch (Exception ignored) {
                            // unparseable filename — skip
                        }
                    }
                });
        } catch (IOException ignored) {
            // directory may not exist yet
        }
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static long computeNextMidnight() {
        return java.time.ZonedDateTime.now()
            .plusDays(1)
            .truncatedTo(java.time.temporal.ChronoUnit.DAYS)
            .toInstant()
            .toEpochMilli();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private long estimateSize(LogRecord record) {
        int msgLen =
            record.getMessage() != null ? record.getMessage().length() : 0;
        String loggerName = record.getLoggerName();
        int loggerLen = loggerName != null ? loggerName.length() : 0;
        long size = 60 + msgLen + loggerLen;
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            size += estimateThrowableSize(thrown);
        }
        return size;
    }

    private static long estimateThrowableSize(Throwable t) {
        long size = 0;
        for (
            Throwable current = t;
            current != null;
            current = current.getCause()
        ) {
            size += 80 + current.toString().length();
            for (StackTraceElement frame : current.getStackTrace()) {
                size += 60 + frame.toString().length();
            }
            for (Throwable suppressed : current.getSuppressed()) {
                size += estimateThrowableSize(suppressed);
            }
        }
        return size;
    }

    /**
     * Absolute base path of the current log file. Package-visible so
     * {@link JULEnhancer} can deduplicate named-file handlers by path.
     */
    Path basePath() {
        return basePath;
    }

    // Visible for testing
    String currentDate() {
        return DATE_FMT.format(currentLocalDate);
    }

    int currentIndex() {
        return currentIndex;
    }

    long bytesWritten() {
        return bytesWritten;
    }
}
