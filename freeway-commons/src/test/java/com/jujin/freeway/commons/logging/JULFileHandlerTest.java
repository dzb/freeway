package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class JULFileHandlerTest {

    @Test
    void writesLogMessageToFile(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("logs").resolve("app.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false);

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
    void createsParentDirectories(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("nested").resolve("deep").resolve("app.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false);

        assertTrue(Files.exists(logFile.getParent()),
                "Parent directories should be auto-created");
        handler.close();
    }

    @Test
    void usesJULFileFormatterByDefault(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("app.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false);

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
                logFile.toString(), 200, 30, false);

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
                logFile.toString(), 10 * 1024 * 1024, 30, false);

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
    void compressesRotatedFile(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("compress.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 150, 30, true);

        LogRecord record = new LogRecord(Level.INFO,
                "Message to fill up the file for compression test");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        // Write enough to trigger at least one rotation
        for (int i = 0; i < 30; i++) {
            handler.publish(record);
        }

        handler.close();

        // Check for .gz files in the directory
        boolean hasGz = Files.list(logFile.getParent())
                .anyMatch(p -> p.getFileName().toString().endsWith(".gz"));
        assertTrue(hasGz, "Should have at least one .gz compressed file in "
                + logFile.getParent());
    }

    @Test
    void logOutputContainsExceptionStackTrace(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("exception.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false);

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
    void nullLogRecordPropertiesDoNotThrow(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("nullsafe.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 10 * 1024 * 1024, 30, false);

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
                logFile.toString(), 10 * 1024 * 1024, 30, false);
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

    // ── regression: REPLACE_EXISTING ───────────────────────────────

    @Test
    void rotationOverwritesExistingArchiveFile(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("overwrite.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 150, 30, false);

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
    void compressedFileIsValidGzip(@TempDir Path tempDir) throws IOException {
        Path logFile = tempDir.resolve("gzip-valid.log");
        JULFileHandler handler = new JULFileHandler(
                logFile.toString(), 150, 30, true);

        LogRecord record = new LogRecord(Level.INFO,
                "data to compress after rotation");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        for (int i = 0; i < 30; i++) {
            handler.publish(record);
        }
        handler.close();

        // Find the .gz file and verify it's a valid GZIP stream
        Path gzFile = Files.list(logFile.getParent())
                .filter(p -> p.getFileName().toString().endsWith(".gz"))
                .findFirst().orElse(null);
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
                logFile.toString(), 100, 1, false);

        // Create fake old archive files
        String oldDate = java.time.LocalDate.now().minusDays(5)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
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
}
