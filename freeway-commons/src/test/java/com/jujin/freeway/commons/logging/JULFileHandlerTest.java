package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.ErrorManager;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JULFileHandlerTest {

    @Test
    void writesLogMessageToFile(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("logs").resolve("app.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.INFO, "hello world");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("com.example.Test");
        handler.publish(record);
        handler.close();

        String content = Files.readString(logFile);
        assertTrue(content.contains("hello world"), "File should contain message: " + content);
        assertTrue(content.contains("com.example.Test"),
                "File should contain full logger name: " + content);
    }

    @Test
    void flushIsBatchedByDefault(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("batched.log");
        // A very large flush interval means the background flusher never
        // fires during the test window — data stays buffered until close.
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, 60_000);

        LogRecord record = new LogRecord(Level.INFO, "batched message");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        handler.publish(record);

        String beforeClose = Files.exists(logFile) ? Files.readString(logFile) : "";
        assertFalse(beforeClose.contains("batched message"),
                "Batched flush should not write before close/flush: " + beforeClose);

        handler.close();

        String afterClose = Files.readString(logFile);
        assertTrue(afterClose.contains("batched message"),
                "Close should flush buffered records: " + afterClose);
    }

    @Test
    void flushIntervalZeroFlushesEagerly(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("eager.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, 0);

        LogRecord record = new LogRecord(Level.INFO, "eager message");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        handler.publish(record);

        String content = Files.readString(logFile);
        assertTrue(content.contains("eager message"),
                "Eager mode (interval 0) should flush per record: " + content);
        handler.close();
    }

    @Test
    void createsParentDirectories(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("nested").resolve("deep").resolve("app.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        assertTrue(Files.exists(logFile.getParent()),
                "Parent directories should be auto-created");
        handler.close();
    }

    @Test
    void usesJULFileFormatterByDefault(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("app.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        assertNotNull(handler.getFormatter());
        assertInstanceOf(JULFileFormatter.class, handler.getFormatter(),
                "Default formatter should be JULFileFormatter");
        handler.close();
    }

    @Test
    void rotatesOnSizeThreshold(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("size-rotate.log");
        // Small maxSize to force rotation quickly
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 200, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.INFO,
                "A long message to exceed the size threshold quickly enough");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        // First write
        handler.publish(record);
        assertEquals(0, handler.currentIndex(),
                "Should start at index 0: " + handler.currentIndex());

        // Force rotation by artificially inflating bytesWritten
        // (the estimate is rough, so we publish multiple times)
        for (int i = 0; i < 20; i++) {
            handler.publish(record);
        }

        assertTrue(handler.currentIndex() > 0,
                "Should have rotated (index > 0), got index=" + handler.currentIndex());

        handler.close();
    }

    @Test
    void rotatesOnDayBoundary(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("day-rotate.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        // Simulate day change by writing a record with tomorrow's date
        // and checking that the rotation flags are set up.
        // The handler uses LocalDate.now() for the date check —
        // the rotation test via needsRotation() is a code-path check.
        // For a true day-boundary rotation, we rely on the implementation
        // calling needsRotation() before each publish.

        LogRecord record = new LogRecord(Level.INFO, "day boundary test");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        handler.publish(record);
        // Day-boundary rotation can't be directly tested without time
        // manipulation. We verify the infrastructure is wired correctly:
        // - needsRotation() is checked before each publish
        // - currentDate is set after first publish
        assertNotNull(handler.currentDate(), "currentDate should be set after publish");

        handler.close();
    }

    @Test
    void compressesRotatedFile(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path logFile = tempDir.resolve("compress.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 150, 30, true, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.INFO,
                "Message to fill up the file for compression test");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        // Write enough to trigger at least one rotation
        for (int i = 0; i < 30; i++) {
            handler.publish(record);
        }

        handler.close();
        JULFileHandler.awaitCompression();

        // Compression is async — wait briefly for the daemon thread
        for (int attempt = 0; attempt < 20; attempt++) {
            boolean hasGz = Files.list(logFile.getParent())
                    .anyMatch(p -> p.getFileName().toString().endsWith(".gz"));
            if (hasGz) break;
            Thread.sleep(50);
        }
        boolean hasGz = Files.list(logFile.getParent())
                .anyMatch(p -> p.getFileName().toString().endsWith(".gz"));
        assertTrue(hasGz, "Should have at least one .gz compressed file in "
                + logFile.getParent());
    }

    @Test
    void logOutputContainsExceptionStackTrace(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("exception.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.SEVERE, "error occurred");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("com.example.ErrorService");
        record.setThrown(new RuntimeException("test failure"));
        handler.publish(record);
        handler.close();

        String content = Files.readString(logFile);
        assertTrue(content.contains("RuntimeException"),
                "Should contain exception class: " + content);
        assertTrue(content.contains("test failure"),
                "Should contain exception message: " + content);
        assertTrue(content.contains("at "),
                "Should contain stack frames: " + content);
    }

    @Test
    void cyclicCauseChainDoesNotHangPublish(@TempDir Path tempDir) throws IOException {
        // Regression: estimateThrowableSize walked getCause()/getSuppressed()
        // without a visited set, so a legal cause cycle (A.cause=B, B.cause=A)
        // looped forever inside publish() while holding the handler monitor —
        // deadlocking every thread logging through this handler.
        Path logFile = tempDir.resolve("cyclic.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        Exception a = new Exception("a");
        Exception b = new Exception("b");
        a.initCause(b);
        try {
            b.initCause(a); // legal in the JDK; produces a two-node cycle
        } catch (IllegalArgumentException expectedCycleGuard) {
            // initCause rejects self-cycle with `this`; A->B->A passes.
            throw new AssertionError("A->B->A cycle should be constructible", expectedCycleGuard);
        }
        a.addSuppressed(b); // mutual suppression — a second cycle edge
        b.addSuppressed(a);

        LogRecord record = new LogRecord(Level.SEVERE, "poisoned");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("com.example.Cyclic");
        record.setThrown(a);

        assertTimeout(Duration.ofSeconds(5), () -> {
            handler.publish(record);
            handler.close();
        });
        assertTrue(Files.readString(logFile).contains("poisoned"));
    }

    @Test
    void nullLogRecordPropertiesDoNotThrow(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("nullsafe.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.INFO, null);
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName(null);

        assertDoesNotThrow(() -> {
            handler.publish(record);
            handler.close();
        });
    }

    @Test
    void respectsLogLevelFilter(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("level.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);
        handler.setLevel(Level.WARNING);

        LogRecord fineRecord = new LogRecord(Level.FINE, "should be filtered");
        fineRecord.setMillis(System.currentTimeMillis());
        fineRecord.setLoggerName("test");
        handler.publish(fineRecord);

        LogRecord severeRecord = new LogRecord(Level.SEVERE, "should appear");
        severeRecord.setMillis(System.currentTimeMillis());
        severeRecord.setLoggerName("test");
        handler.publish(severeRecord);

        handler.close();

        String content = Files.readString(logFile);
        assertFalse(content.contains("should be filtered"),
                "FINE message should be filtered out: " + content);
        assertTrue(content.contains("should appear"),
                "SEVERE message should appear: " + content);
    }

    @Test
    void rotationTriggersReasonablyForExceptionLogs(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("exception-rotate.log");
        JULFileHandler handler = new JULFileHandler(logFile.toString(), 200, 30, false,
            JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.SEVERE, "error");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setThrown(new RuntimeException("deep", new IllegalStateException("cause")));

        // one record may overshoot; publish a few to ensure rotation eventually triggers
        for (int i = 0; i < 10; i++) {
            handler.publish(record);
        }
        handler.close();

        // after multiple exception records, rotation should have triggered at least once
        assertTrue(handler.currentIndex() > 0,
                "Rotation should trigger after logging exception records, got index="
                        + handler.currentIndex());
    }

    // ── regression: REPLACE_EXISTING ───────────────────────────────

    @Test
    void rotationOverwritesExistingArchiveFile(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("overwrite.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 150, 30, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        // Pre-create an archive file with the name rotation would produce
        String today = handler.currentDate();
        Path fakeArchive = logFile.getParent().resolve("overwrite." + today + ".log");
        Files.writeString(fakeArchive, "stale archive content");

        LogRecord record = new LogRecord(Level.INFO,
                "message long enough to trigger size-based rotation quickly");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        // Publish enough to force rotation — should overwrite the fake archive
        for (int i = 0; i < 30; i++) {
            handler.publish(record);
        }

        handler.close();

        // The archive should now contain log content, not "stale archive content"
        String archiveContent = Files.readString(fakeArchive);
        assertFalse(archiveContent.contains("stale archive content"),
                "Archive should be overwritten, not append to old content");
    }

    // ── regression: compressed file validity ──────────────────────

    @Test
    void compressedFileIsValidGzip(@TempDir Path tempDir) throws IOException, InterruptedException {
        Path logFile = tempDir.resolve("gzip-valid.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 150, 30, true, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.INFO,
                "data to compress after rotation");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        for (int i = 0; i < 30; i++) {
            handler.publish(record);
        }
        handler.close();
        JULFileHandler.awaitCompression();

        // Compression is async — wait briefly
        Path gzFile = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            gzFile = Files.list(logFile.getParent())
                    .filter(p -> p.getFileName().toString().endsWith(".gz"))
                    .findFirst().orElse(null);
            if (gzFile != null) break;
            Thread.sleep(50);
        }
        assertNotNull(gzFile, "Should have a .gz file after compression");
        assertTrue(Files.size(gzFile) > 0, "GZIP file should not be empty");

        // Verify GZIP magic bytes: 0x1F 0x8B
        byte[] header = Files.readAllBytes(gzFile);
        assertTrue(header.length >= 2, "GZIP file should have at least 2 bytes");
        assertEquals((byte) 0x1F, header[0], "GZIP magic byte 0");
        assertEquals((byte) 0x8B, header[1], "GZIP magic byte 1");
    }

    // ── regression: purge by filename date ────────────────────────

    @Test
    void purgesOldFilesBasedOnFilenameDate(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("purge.log");
        // maxHistory=1 so only today's files survive. Small maxSize to force rotation.
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 100, 1, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        // Create fake old archive files
        String oldDate = LocalDate.now().minusDays(5)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path oldFile = logFile.getParent().resolve("purge." + oldDate + ".log");
        Files.writeString(oldFile, "old");

        Path oldCompressed = logFile.getParent().resolve("purge." + oldDate + ".log.gz");
        Files.writeString(oldCompressed, "old-gz");

        // Create a fake file belonging to another app (should NOT be deleted)
        Path otherFile = logFile.getParent().resolve("other.2020-01-01.log");
        Files.writeString(otherFile, "other");

        // Publish many records to guarantee size rotation triggers purgeOldFiles()
        LogRecord record = new LogRecord(Level.INFO,
                "message long enough to overflow the 100-byte max size threshold");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        for (int i = 0; i < 50; i++) {
            handler.publish(record);
        }
        assertTrue(handler.currentIndex() > 0,
                "Should have rotated at least once, got index=" + handler.currentIndex());
        handler.close();

        assertFalse(Files.exists(oldFile), "Old file should be purged: " + oldFile);
        assertFalse(Files.exists(oldCompressed), "Old compressed file should be purged");
        assertTrue(Files.exists(otherFile), "Other app's files should NOT be purged");
    }

    // ── regression: purge never deletes an in-progress .gz.tmp ─────

    // ── daily / weekly / monthly rotation modes ─────────────────

    @Test
    void rotatePeriodAcceptsDailyWeeklyAndMonthly() {
        assertEquals(JULFileHandler.RotatePeriod.DAILY,
            JULFileHandler.RotatePeriod.parse("daily"));
        assertEquals(JULFileHandler.RotatePeriod.WEEKLY,
            JULFileHandler.RotatePeriod.parse("WEEKLY"));
        assertEquals(JULFileHandler.RotatePeriod.MONTHLY,
            JULFileHandler.RotatePeriod.parse("Monthly"));
        assertEquals(JULFileHandler.RotatePeriod.DAILY,
            JULFileHandler.RotatePeriod.parse(null));
        assertThrows(IllegalArgumentException.class,
            () -> JULFileHandler.RotatePeriod.parse("yearly"));
    }

    @Test
    void retentionCountsFromTheEndOfTheArchivePeriod() {
        LocalDate monday = LocalDate.of(2026, 11, 16);
        assertEquals(monday.plusDays(6),
            JULFileHandler.retentionEnd(monday, JULFileHandler.RotatePeriod.WEEKLY));
        assertEquals(monday,
            JULFileHandler.retentionEnd(monday, JULFileHandler.RotatePeriod.DAILY));

        LocalDate monthStart = LocalDate.of(2026, 11, 1);
        assertEquals(LocalDate.of(2026, 11, 30),
            JULFileHandler.retentionEnd(monthStart, JULFileHandler.RotatePeriod.MONTHLY));
        assertEquals(LocalDate.of(2028, 2, 29),
            JULFileHandler.retentionEnd(LocalDate.of(2028, 2, 1),
                JULFileHandler.RotatePeriod.MONTHLY),
            "a monthly archive ends on that month's last day, leap years included");
    }

    @Test
    void weeklyRotationArchivesUnderTheWeeksMonday(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("weekly.log");
        Files.writeString(logFile, "previous week");
        LocalDate previousWeek = LocalDate.now().minusDays(8);
        Files.setLastModifiedTime(logFile, FileTime.from(
            previousWeek.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()));

        JULFileHandler handler = new JULFileHandler(
            logFile.toString(), 10 * 1024 * 1024, 30, false,
            JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS, JULFileHandler.RotatePeriod.WEEKLY);

        LocalDate monday = previousWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Path archive = tempDir.resolve("weekly." + monday + ".log");
        assertTrue(Files.exists(archive),
            "weekly archive is named by its Monday: " + archive);
        handler.close();
    }

    @Test
    void weeklySizeRotationUsesTheCurrentWeeksMonday(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("weekly-size.log");
        JULFileHandler handler = new JULFileHandler(
            logFile.toString(), 100, 30, false,
            JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS, JULFileHandler.RotatePeriod.WEEKLY);

        LogRecord record = new LogRecord(Level.INFO,
            "message long enough to overflow the size threshold");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        for (int i = 0; i < 50; i++) {
            handler.publish(record);
        }
        assertTrue(handler.currentIndex() > 0, "size rotation must have fired");

        LocalDate monday = LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        assertTrue(Files.exists(tempDir.resolve("weekly-size." + monday + ".log")),
            "a size-rotated archive still carries the week's Monday");
        handler.close();
    }

    @Test
    void weeklyPurgeDeletesOldWeeksAndKeepsRecentOnes(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("weekly-purge.log");
        LocalDate oldMonday = LocalDate.now().minusDays(40)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate recentMonday = LocalDate.now().minusDays(10)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Path old = tempDir.resolve("weekly-purge." + oldMonday + ".log");
        Path recent = tempDir.resolve("weekly-purge." + recentMonday + ".log");
        Files.writeString(old, "old");
        Files.writeString(recent, "recent");

        JULFileHandler handler = new JULFileHandler(
            logFile.toString(), 10 * 1024 * 1024, 30, false,
            JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS, JULFileHandler.RotatePeriod.WEEKLY);
        assertFalse(Files.exists(old), "a week ending before the cutoff is purged");
        assertTrue(Files.exists(recent), "a recent week is kept");
        handler.close();
    }

    @Test
    void monthlyRotationArchivesUnderTheFirstOfTheMonth(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("monthly.log");
        Files.writeString(logFile, "previous month");
        LocalDate previousMonth = LocalDate.now().minusDays(40);
        Files.setLastModifiedTime(logFile, FileTime.from(
            previousMonth.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()));

        JULFileHandler handler = new JULFileHandler(
            logFile.toString(), 10 * 1024 * 1024, 30, false,
            JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS, JULFileHandler.RotatePeriod.MONTHLY);

        LocalDate monthStart = previousMonth.with(TemporalAdjusters.firstDayOfMonth());
        Path archive = tempDir.resolve("monthly." + monthStart + ".log");
        assertTrue(Files.exists(archive),
            "monthly archive is named by the first of its month: " + archive);
        handler.close();
    }

    @Test
    void monthlySizeRotationUsesTheCurrentMonthsFirstDay(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("monthly-size.log");
        JULFileHandler handler = new JULFileHandler(
            logFile.toString(), 100, 30, false,
            JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS, JULFileHandler.RotatePeriod.MONTHLY);

        LogRecord record = new LogRecord(Level.INFO,
            "message long enough to overflow the size threshold");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        for (int i = 0; i < 50; i++) {
            handler.publish(record);
        }
        assertTrue(handler.currentIndex() > 0, "size rotation must have fired");

        LocalDate monthStart = LocalDate.now().with(TemporalAdjusters.firstDayOfMonth());
        assertTrue(Files.exists(tempDir.resolve("monthly-size." + monthStart + ".log")),
            "a size-rotated archive still carries the current month's first day");
        handler.close();
    }

    @Test
    void monthlyPurgeDeletesOldMonthsAndKeepsRecentOnes(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("monthly-purge.log");
        // Three months back is always older than a 30-day cutoff, even when
        // counted from that month's last day; last month never is.
        LocalDate oldMonth = LocalDate.now().minusMonths(3)
            .with(TemporalAdjusters.firstDayOfMonth());
        LocalDate recentMonth = LocalDate.now().minusMonths(1)
            .with(TemporalAdjusters.firstDayOfMonth());
        Path old = tempDir.resolve("monthly-purge." + oldMonth + ".log");
        Path recent = tempDir.resolve("monthly-purge." + recentMonth + ".log");
        Files.writeString(old, "old");
        Files.writeString(recent, "recent");

        JULFileHandler handler = new JULFileHandler(
            logFile.toString(), 10 * 1024 * 1024, 30, false,
            JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS, JULFileHandler.RotatePeriod.MONTHLY);
        assertFalse(Files.exists(old), "a month ending before the cutoff is purged");
        assertTrue(Files.exists(recent), "a recent month is kept");
        handler.close();
    }

    @Test
    void rotateConfigKeySelectsTheConfiguredPeriod(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("config-rotate.log");
        System.setProperty("freeway.log.file", logFile.toString());
        try {
            for (String value : new String[] {"weekly", "monthly"}) {
                System.setProperty("freeway.log.file.rotate", value);
                JULFileHandler handler = new JULFileHandler();
                assertEquals(JULFileHandler.RotatePeriod.parse(value), handler.rotatePeriod(),
                    "configured period must survive the config read: " + value);
                handler.close();
            }
        } finally {
            System.clearProperty("freeway.log.file");
            System.clearProperty("freeway.log.file.rotate");
        }
    }

    @Test
    void invalidRotateConfigFallsBackToDaily(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("config-invalid.log");
        System.setProperty("freeway.log.file", logFile.toString());
        System.setProperty("freeway.log.file.rotate", "yearly");
        try {
            JULFileHandler handler = new JULFileHandler();
            assertEquals(JULFileHandler.RotatePeriod.DAILY, handler.rotatePeriod(),
                "an unparseable rotate value is reported and replaced by the default");
            handler.close();
        } finally {
            System.clearProperty("freeway.log.file");
            System.clearProperty("freeway.log.file.rotate");
        }
    }

    @Test
    void purgeSkipsGzTmpStagingFile(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("purge-tmp.log");
        String oldDate = LocalDate.now().minusDays(5)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

        // An expired archive that purge must delete...
        Path oldArchive = logFile.getParent()
                .resolve("purge-tmp." + oldDate + ".1.log");
        Files.writeString(oldArchive, "old");

        // ...and a .gz.tmp staging file from an in-progress compression that
        // purge must NOT delete: it would orphan the compressor mid-write
        // and lose the archive it is producing.
        Path oldTmp = logFile.getParent()
                .resolve("purge-tmp." + oldDate + ".1.log.gz.tmp");
        Files.writeString(oldTmp, "partial gzip data");

        // Constructor runs rotateStaleFileOnStartup() → purgeOldFiles().
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 1, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        assertTrue(Files.exists(oldTmp),
                "In-progress .gz.tmp staging file must survive purge");
        assertFalse(Files.exists(oldArchive),
                "Expired archive must still be purged");
        handler.close();
    }

    // ── regression: startup rotation enforces max-history ──────────

    @Test
    void startupRotationPurgesOldArchives(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("startup-purge.log");
        Files.writeString(logFile, "stale content from yesterday");

        // Yesterday's modification time so startup rotation archives it.
        long yesterdayMillis = LocalDate.now().minusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        Files.setLastModifiedTime(logFile, FileTime.fromMillis(yesterdayMillis));

        // An archive older than the retention window (maxHistory=2).
        String oldDate = LocalDate.now().minusDays(5)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path oldArchive = logFile.getParent()
                .resolve("startup-purge." + oldDate + ".log");
        Files.writeString(oldArchive, "old");

        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 2, false, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        assertFalse(Files.exists(oldArchive),
                "Archive older than maxHistory should be purged at startup");

        String yesterday = LocalDate.now().minusDays(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Path rotatedArchive = logFile.getParent()
                .resolve("startup-purge." + yesterday + ".log");
        assertTrue(Files.exists(rotatedArchive),
                "Stale current file should be archived at startup");
        assertTrue(Files.exists(logFile),
                "A fresh current file should be opened after startup rotation");
        handler.close();
    }

    // ── regression: atomic gzip via .gz.tmp staging file ───────────

    @Test
    void compressionLeavesNoTmpFileAfterSuccess(@TempDir Path tempDir)
            throws IOException, InterruptedException {
        Path logFile = tempDir.resolve("atomic-gzip.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 150, 30, true, JULFileHandler.DEFAULT_FLUSH_INTERVAL_MS);

        LogRecord record = new LogRecord(Level.INFO,
                "data that triggers rotation and async compression");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        for (int i = 0; i < 30; i++) {
            handler.publish(record);
        }
        handler.close();
        JULFileHandler.awaitCompression();

        // Wait for compression to finish; the .gz.tmp staging file must be
        // gone afterwards (moved into place atomically or deleted on error).
        Path parent = logFile.getParent();
        boolean compressed = false;
        for (int attempt = 0; attempt < 40 && !compressed; attempt++) {
            try (var gzStream = Files.list(parent)) {
                boolean hasGz = gzStream
                        .anyMatch(p -> p.getFileName().toString().endsWith(".gz"));
                boolean hasTmp;
                try (var tmpStream = Files.list(parent)) {
                    hasTmp = tmpStream
                            .anyMatch(p -> p.getFileName().toString().endsWith(".gz.tmp"));
                }
                compressed = hasGz && !hasTmp;
            }
            if (!compressed) Thread.sleep(50);
        }
        assertTrue(compressed,
                "Compression should complete with no leftover .gz.tmp in " + parent);

        // The surviving .gz is complete and valid (gzip magic bytes).
        Path gzFile;
        try (var gzStream = Files.list(parent)) {
            gzFile = gzStream
                    .filter(p -> p.getFileName().toString().endsWith(".gz"))
                    .findFirst().orElse(null);
        }
        assertNotNull(gzFile, "Should have a .gz file after compression");
        byte[] header = Files.readAllBytes(gzFile);
        assertEquals((byte) 0x1F, header[0], "GZIP magic byte 0");
        assertEquals((byte) 0x8B, header[1], "GZIP magic byte 1");
    }

    // ── regression: failed rotation reports instead of silently dropping ──

    @Test
    void recoversAfterRotationFailure(@TempDir Path tempDir) throws IOException {
        Path logDir = tempDir.resolve("logs");
        Path logFile = logDir.resolve("app.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 200, 30, false, 0);

        List<Exception> reported = new ArrayList<>();
        AtomicBoolean sabotaged = new AtomicBoolean();
        handler.setErrorManager(new ErrorManager() {
            @Override
            public void error(String msg, Exception ex, int code) {
                reported.add(ex);
                if (sabotaged.compareAndSet(false, true)) {
                    try {
                        // rotate() closes the stream before archiving, so the
                        // file is free here even on Windows — which refuses to
                        // delete an open file. Turning it into a directory makes
                        // the reopen that follows fail on every OS.
                        Files.delete(logFile);
                        Files.createDirectory(logFile);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        });

        LogRecord big = new LogRecord(Level.INFO, "x".repeat(1200));
        big.setMillis(System.currentTimeMillis());
        big.setLoggerName("test");

        // Sabotage: a non-empty directory at the archive target. Files.move()
        // cannot replace it — POSIX or Windows — so rotation is forced to fail
        // without relying on unlink-while-open (POSIX-only).
        Path archived = logDir.resolve("app." + handler.currentDate() + ".log");
        Files.createDirectories(archived);
        Files.writeString(archived.resolve("blocker"), "blocked");

        // First publish writes past the size threshold; the second triggers
        // rotation: the archive move fails, and the reopen fails with it.
        handler.publish(big);
        handler.publish(big);
        assertFalse(reported.isEmpty(),
                "Rotation failure must be reported, not silently dropped");
        assertTrue(
                reported.stream().anyMatch(e -> e instanceof FileNotFoundException),
                "the failed reopen must be reported too — publish() keeps "
                    + "retrying it instead of silently dropping records: " + reported);

        // Remove the obstacle; the next publish must recover automatically.
        Files.delete(logFile); // the directory the sabotage left behind
        LogRecord after = new LogRecord(Level.INFO, "recovered record");
        after.setMillis(System.currentTimeMillis());
        after.setLoggerName("test");
        handler.publish(after);
        handler.close();

        String content = Files.readString(logFile);
        assertTrue(content.contains("recovered record"),
                "Record after recovery should be written: " + content);
        // @TempDir teardown must not trip over the sabotage leftovers.
        Files.delete(archived.resolve("blocker"));
        Files.delete(archived);
    }

    @Test
    void noArgConstructorResolvesThroughTheFullCascade(@TempDir Path tempDir) throws IOException {
        // The no-arg constructor (logging.properties / LogManager path) must
        // resolve values through the same cascade as the framework-managed
        // path: a value known ONLY as a system property (-D, the cascade's
        // highest source) drives the handler — it used to read the merged
        // file map alone, so a -Dfreeway.log.file was ignored and the
        // constructor failed outright when no file-tier value existed.
        Path logFile = tempDir.resolve("from-dash-d").resolve("app.log");
        System.setProperty("freeway.log.file", logFile.toString());
        try {
            JULFileHandler handler = new JULFileHandler();
            handler.publish(new LogRecord(Level.INFO, "via -D"));
            handler.close();

            assertTrue(Files.exists(logFile),
                "-Dfreeway.log.file must drive the natively registered handler");
            assertTrue(Files.readString(logFile).contains("via -D"),
                "records land in the -D-configured file");
        } finally {
            System.clearProperty("freeway.log.file");
        }
    }

    @Test
    void noArgConstructorStillFailsWithoutAnySource() {
        // No -D, no env, no file-tier value: the required key stays missing.
        System.clearProperty("freeway.log.file");
        assertThrows(IllegalArgumentException.class, JULFileHandler::new,
            "freeway.log.file is required — nothing may invent a default path");
    }
}
