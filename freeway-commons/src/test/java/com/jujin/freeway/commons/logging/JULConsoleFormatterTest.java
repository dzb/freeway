package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class JULConsoleFormatterTest {

    // ── format (no color) ────────────────────────────────────────────

    @Test
    void formatBasicRecord() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.INFO, "Hello world");
        record.setLoggerName("com.jujin.freeway.db.DbModule");
        record.setMillis(0);

        String output = formatter.format(record);

        // full timestamp: yyyy-MM-dd HH:mm:ss.SSS
        String expectedDate = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(0));
        assertTrue(output.startsWith(expectedDate), "should start with full timestamp: " + output);
        assertTrue(output.contains("INFO   "), "level should be padded to 7: " + output);
        assertTrue(output.contains("Hello world"), "should contain message: " + output);
        assertTrue(output.contains("c.j.f.db.DbModule"),
                "should contain abbreviated logger: " + output);
        assertTrue(output.endsWith("\n"), "should end with newline");
        assertFalse(output.contains("\033["), "should not contain ANSI codes: " + output);
    }

    @Test
    void formatNullMessage() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.INFO, null);
        record.setLoggerName("test.Null");

        String output = formatter.format(record);
        assertTrue(output.endsWith("\n"));
    }

    @Test
    void formatMessageReturnsMessageOnly() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.INFO, "Hello {0}");
        record.setParameters(new Object[] { "world" });

        assertEquals("Hello world", formatter.formatMessage(record));
    }

    @Test
    void formatLevelNames() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.WARNING, "msg");
        record.setLoggerName("test.Level");

        String output = formatter.format(record);
        assertTrue(output.contains("WARNING"), "should contain level name: " + output);
    }

    // ── color ────────────────────────────────────────────────────────

    @Test
    void formatWithColor() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(true);
        LogRecord record = new LogRecord(Level.WARNING, "Warning");
        record.setLoggerName("test.Logger");

        String output = formatter.format(record);
        assertTrue(output.contains("\033["), "should contain ANSI codes: " + output);
        assertTrue(output.contains("Warning"), "should contain message: " + output);
    }

    @Test
    void severeLevelIsRedBold() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(true);
        LogRecord record = new LogRecord(Level.SEVERE, "Critical");
        record.setLoggerName("test.Critical");

        String output = formatter.format(record);
        assertTrue(output.contains("\033[31m\033[1m"), "SEVERE should be red+bold: " + output);
    }

    @Test
    void infoLevelIsGreen() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(true);
        LogRecord record = new LogRecord(Level.INFO, "OK");
        record.setLoggerName("test.Info");

        String output = formatter.format(record);
        assertTrue(output.contains("\033[32m"), "INFO should be green: " + output);
    }

    @Test
    void fineLevelIsGray() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(true);
        LogRecord record = new LogRecord(Level.FINE, "Debug");
        record.setLoggerName("test.Debug");

        String output = formatter.format(record);
        assertTrue(output.contains("\033[90m"), "FINE should be gray: " + output);
    }

    @Test
    void useColorReflectsConstructorArg() {
        assertTrue(new JULConsoleFormatter(true).useColor());
        assertFalse(new JULConsoleFormatter(false).useColor());
    }

    // ── throwable ────────────────────────────────────────────────────

    @Test
    void formatWithThrowable() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.SEVERE, "Boom");
        record.setLoggerName("test.Throwing");
        record.setThrown(new RuntimeException("fail"));

        String output = formatter.format(record);
        assertTrue(output.contains("Boom"), "should contain message");
        assertTrue(output.contains("RuntimeException"), "should contain exception class");
        assertTrue(output.contains("fail"), "should contain exception message");
        assertTrue(output.contains("      at "), "frames should be indented under log line: " + output);
    }

    @Test
    void formatThrowableWithCause() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.SEVERE, "Wrapped");
        record.setLoggerName("test.Cause");
        record.setThrown(new RuntimeException("outer",
                new IllegalStateException("inner")));

        String output = formatter.format(record);
        assertTrue(output.contains("RuntimeException"));
        assertTrue(output.contains("outer"));
        assertTrue(output.contains("  Caused by: "));
        assertTrue(output.contains("IllegalStateException"));
        assertTrue(output.contains("inner"));
    }

    @Test
    void formatThrowableWithSuppressedExceptionAndCause() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        RuntimeException rootCause = new RuntimeException("root cause");
        IOException supressed = new IOException("close failed", rootCause);
        RuntimeException outer = new RuntimeException("outer");
        outer.addSuppressed(supressed);

        LogRecord record = new LogRecord(Level.SEVERE, "Suppressed test");
        record.setLoggerName("test.Suppressed");
        record.setThrown(outer);

        String output = formatter.format(record);
        // Suppressed exception should appear
        assertTrue(output.contains("Suppressed:"), "Should render suppressed: " + output);
        assertTrue(output.contains("close failed"),
                "Should render suppressed message: " + output);
        // The suppressed exception's CAUSE should also appear
        assertTrue(output.contains("Caused by:"),
                "Suppressed's cause chain should be rendered: " + output);
        assertTrue(output.contains("root cause"),
                "Suppressed's root cause should appear: " + output);
    }
}
