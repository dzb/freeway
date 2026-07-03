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
 * }</pre>
 *
 * <p>All settings have sensible defaults. Only {@code freeway.log.file}
 * is required to activate file logging.
 *
 * <p>Built entirely on JDK APIs — no external dependencies.
 */
public final class JULFileHandler extends StreamHandler {

    static final long DEFAULT_MAX_SIZE = 100L * 1024 * 1024; // 100 MB
    static final int DEFAULT_MAX_HISTORY = 30;                // days
    static final boolean DEFAULT_COMPRESS = true;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Daemon thread for async GZIP compression so rotation never blocks logging. */
    private static final ExecutorService COMPRESSOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "freeway-log-compressor");
        t.setDaemon(true);
        return t;
    });

    private final Path basePath;
    private final long maxSize;
    private final int maxHistory;
    private final boolean compress;

    private LocalDate currentLocalDate;
    private long nextMidnightMillis;
    private int currentIndex;
    private long bytesWritten;

    /**
     * No-arg constructor for {@code logging.properties} / {@code LogManager}
     * instantiation. Reads configuration from system properties.
     */
    public JULFileHandler() throws IOException {
        this(
            requiredProperty("freeway.log.file"),
            longProperty("freeway.log.file.max-size", DEFAULT_MAX_SIZE),
            intProperty("freeway.log.file.max-history", DEFAULT_MAX_HISTORY),
            booleanProperty("freeway.log.file.compress", DEFAULT_COMPRESS));
    }

    /**
     * Programmatic constructor.
     *
     * @param filePath   path to the log file (e.g. {@code logs/app.log})
     * @param maxSize    max bytes before size-based rotation
     * @param maxHistory days to retain
     * @param compress   whether to gzip rotated files
     */
    public JULFileHandler(String filePath, long maxSize, int maxHistory, boolean compress)
            throws IOException {
        this.basePath = Paths.get(filePath).toAbsolutePath();
        this.maxSize = Math.max(1024, maxSize);
        this.maxHistory = Math.max(1, maxHistory);
        this.compress = compress;
        this.currentLocalDate = LocalDate.now();
        this.nextMidnightMillis = computeNextMidnight();
        this.currentIndex = 0;
        this.bytesWritten = 0;

        Files.createDirectories(basePath.getParent());
        setFormatter(new JULFileFormatter());
        setLevel(Level.ALL);
        rotateStaleFileOnStartup();
        openCurrentFile();
    }

    /** If the log file exists and was last modified before today, archive it. */
    private void rotateStaleFileOnStartup() {
        if (!Files.exists(basePath) || fileSize(basePath) == 0) return;
        try {
            LocalDate fileDate = LocalDate.ofInstant(
                    Files.getLastModifiedTime(basePath).toInstant(),
                    java.time.ZoneId.systemDefault());
            if (fileDate.isBefore(currentLocalDate)) {
                Path archived = archivedPath(DATE_FMT.format(fileDate), 0);
                Files.move(basePath, archived, StandardCopyOption.REPLACE_EXISTING);
                if (compress) {
                    COMPRESSOR.execute(() -> compressFile(archived));
                }
            }
        } catch (IOException e) {
            // best-effort — open the existing file if rotation fails
        }
    }

    private static long fileSize(Path path) {
        try { return Files.size(path); }
        catch (IOException e) { return 0; }
    }

    // ── property helpers ─────────────────────────────────────────────

    private static String requiredProperty(String key) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException(
                    key + " is required to activate JULFileHandler");
        }
        return val;
    }

    private static long longProperty(String key, long defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Long.parseLong(val.strip());
    }

    private static int intProperty(String key, int defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Integer.parseInt(val.strip());
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        String val = System.getProperty(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val.strip());
    }

    // ── publish ─────────────────────────────────────────────────────

    @Override
    public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) return;

        try {
            if (needsRotation()) {
                rotate();
            }
        } catch (IOException e) {
            reportError("Rotation failed", e, ErrorManager.WRITE_FAILURE);
            return;
        }

        super.publish(record);
        bytesWritten += estimateSize(record);
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
            reportError("Failed to close current log stream", e,
                    ErrorManager.WRITE_FAILURE);
        }

        // Archive the current file (best-effort)
        try {
            if (Files.exists(basePath) && Files.size(basePath) > 0) {
                Path archived = archivedPath(DATE_FMT.format(currentLocalDate), currentIndex);
                Files.move(basePath, archived, StandardCopyOption.REPLACE_EXISTING);
                if (compress) {
                    COMPRESSOR.execute(() -> compressFile(archived));
                }
            }
        } catch (IOException e) {
            reportError("Failed to archive log file", e,
                    ErrorManager.WRITE_FAILURE);
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
                new FileOutputStream(basePath.toFile(), true));
        setOutputStream(out);
        bytesWritten = fileSize(basePath);
    }

    private void closeOutputStream() {
        super.close();
    }

    private void compressFile(Path file) {
        Path gzFile = file.getParent().resolve(file.getFileName() + ".gz");
        try (FileInputStream fin = new FileInputStream(file.toFile());
             FileOutputStream fos = new FileOutputStream(gzFile.toFile());
             OutputStream gout = new GZIPOutputStream(fos)) {
            fin.transferTo(gout);
        } catch (IOException e) {
            reportError("Failed to compress " + file.getFileName(), e,
                    ErrorManager.GENERIC_FAILURE);
            return;
        }
        try {
            Files.delete(file);
        } catch (IOException e) {
            reportError("Failed to delete uncompressed " + file.getFileName(), e,
                    ErrorManager.GENERIC_FAILURE);
        }
    }

    private void purgeOldFiles() {
        String stem = stripExtension(basePath.getFileName().toString());
        int dateStart = stem.length() + 1; // after "stem."
        LocalDate cutoff = LocalDate.now().minusDays(maxHistory);
        try (var paths = Files.list(basePath.getParent())) {
            paths.filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(stem + ".") && !name.equals(
                                basePath.getFileName().toString());
                    })
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        if (name.length() >= dateStart + 10) {
                            try {
                                LocalDate fileDate = LocalDate.parse(
                                        name.substring(dateStart, dateStart + 10), DATE_FMT);
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
                .plusDays(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .toInstant().toEpochMilli();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private long estimateSize(LogRecord record) {
        int msgLen = record.getMessage() != null ? record.getMessage().length() : 0;
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
        for (Throwable current = t; current != null; current = current.getCause()) {
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

    // Visible for testing
    String currentDate() { return DATE_FMT.format(currentLocalDate); }
    int currentIndex() { return currentIndex; }
    long bytesWritten() { return bytesWritten; }
}
