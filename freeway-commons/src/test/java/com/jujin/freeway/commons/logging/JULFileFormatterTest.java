package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class JULFileFormatterTest {

    private final JULFileFormatter formatter = new JULFileFormatter();

    @Test
    void includesIso8601Timestamp() {
        LogRecord record = new LogRecord(Level.INFO, "hello");
        record.setMillis(1719742500123L); // 2026-06-30T10:15:00.123Z → local time
        record.setLoggerName("com.example.Service");

        String out = formatter.format(record);
        // ISO 8601 pattern: yyyy-MM-dd'T'HH:mm:ss.SSSXXX
        assertTrue(out.contains("T"), "Should contain ISO 8601 T separator: " + out);
        assertTrue(out.contains(":") && out.contains("-"),
                "Should contain ISO 8601 timestamp: " + out);
    }

    @Test
    void usesFullLoggerName() {
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("com.jujin.freeway.db.DbModule");

        String out = formatter.format(record);
        assertTrue(out.contains("com.jujin.freeway.db.DbModule"),
                "Should contain full logger name, not abbreviated: " + out);
        assertFalse(out.contains("c.j.f."),
                "Should NOT abbreviate logger name: " + out);
    }

    @Test
    void levelIsLeftAlignedToEightChars() {
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setMillis(System.currentTimeMillis());

        String out = formatter.format(record);
        // "INFO" padded to 8 chars: "INFO    "
        assertTrue(out.contains("INFO    "),
                "INFO should be padded to 8 chars: " + out);

        // WARNING is exactly 7 chars, padded to 8
        LogRecord warnRecord = new LogRecord(Level.WARNING, "msg");
        warnRecord.setMillis(System.currentTimeMillis());
        String warnOut = formatter.format(warnRecord);
        assertTrue(warnOut.contains("WARNING "),
                "WARNING should be padded to 8 chars: " + warnOut);
    }

    @Test
    void includesThreadName() {
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setMillis(System.currentTimeMillis());

        String out = formatter.format(record);
        String threadName = Thread.currentThread().getName();
        assertTrue(out.contains("[" + threadName + "]"),
                "Should contain thread name in brackets: " + out);
    }

    @Test
    void noAnsiColorCodes() {
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertFalse(out.contains("\033["),
                "Should not contain ANSI escape codes: " + out);
        assertFalse(out.contains("\033[0m"),
                "Should not contain ANSI reset: " + out);
    }

    @Test
    void endsWithNewline() {
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setMillis(System.currentTimeMillis());

        String out = formatter.format(record);
        assertTrue(out.endsWith("\n"), "Should end with newline");
    }

    @Test
    void handlesNullMessage() {
        LogRecord record = new LogRecord(Level.INFO, null);
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertNotNull(out);
        assertTrue(out.endsWith("\n"), "Should not NPE on null message: " + out);
    }

    @Test
    void handlesNullLoggerName() {
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setMillis(System.currentTimeMillis());

        String out = formatter.format(record);
        assertNotNull(out);
        assertTrue(out.endsWith("\n"), "Should not NPE on null logger: " + out);
    }

    @Test
    void formatsThrowableWithStackTrace() {
        LogRecord record = new LogRecord(Level.SEVERE, "bang");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setThrown(new RuntimeException("test exception"));

        String out = formatter.format(record);
        assertTrue(out.contains("RuntimeException"),
                "Should contain exception class: " + out);
        assertTrue(out.contains("test exception"),
                "Should contain exception message: " + out);
        assertTrue(out.contains("at "),
                "Should contain stack frames with 'at': " + out);
    }

    @Test
    void formatsThrowableWithCauseChain() {
        RuntimeException cause = new RuntimeException("root cause");
        RuntimeException outer = new RuntimeException("outer", cause);

        LogRecord record = new LogRecord(Level.SEVERE, "chained");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setThrown(outer);

        String out = formatter.format(record);
        assertTrue(out.contains("Caused by:"),
                "Should contain 'Caused by:' for chained exception: " + out);
        assertTrue(out.contains("root cause"),
                "Should contain root cause message: " + out);
        assertTrue(out.contains("outer"),
                "Should contain outer exception message: " + out);
    }

    @Test
    void fineLevelIsFormatted() {
        LogRecord record = new LogRecord(Level.FINE, "debug detail");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertTrue(out.contains("FINE"),
                "Should contain FINE level: " + out);
    }

    @Test
    void severeLevelIsFormatted() {
        LogRecord record = new LogRecord(Level.SEVERE, "critical");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertTrue(out.contains("SEVERE"),
                "Should contain SEVERE level: " + out);
    }

    @Test
    void messageWithFormatParams() {
        LogRecord record = new LogRecord(Level.INFO, "User {0} logged in");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setParameters(new Object[]{"Alice"});

        String out = formatter.format(record);
        assertTrue(out.contains("User Alice logged in"),
                "Formatter should expand JUL {0} parameters: " + out);
    }

    // ── regression: suppressed exception cause chain ───────────────

    @Test
    void suppressedExceptionRendersItsOwnCauseChain() {
        RuntimeException rootCause = new RuntimeException("root");
        IOException closeException = new IOException("close failed", rootCause);
        RuntimeException outer = new RuntimeException("outer");
        outer.addSuppressed(closeException);

        LogRecord record = new LogRecord(Level.SEVERE, "suppressed test");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setThrown(outer);

        String out = formatter.format(record);
        // The suppressed IOException should appear
        assertTrue(out.contains("Suppressed:"), "Should render suppressed header: " + out);
        assertTrue(out.contains("close failed"),
                "Should render suppressed exception message: " + out);
        // The suppressed exception's CAUSE should also be rendered
        assertTrue(out.contains("Caused by:"),
                "Suppressed exception's cause chain should be rendered: " + out);
        assertTrue(out.contains("root"),
                "Suppressed exception's root cause message should appear: " + out);
    }

    @Test
    void nestedSuppressedExceptionsAreRecursivelyRendered() {
        RuntimeException leaf = new RuntimeException("leaf");
        RuntimeException middle = new RuntimeException("middle");
        middle.addSuppressed(leaf);
        RuntimeException outer = new RuntimeException("outer");
        outer.addSuppressed(middle);

        LogRecord record = new LogRecord(Level.SEVERE, "nested suppressed");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setThrown(outer);

        String out = formatter.format(record);
        // Both middle and leaf should appear as suppressed
        int suppressedCount = out.split("Suppressed:").length - 1;
        assertTrue(suppressedCount >= 2,
                "Should render nested suppressed exceptions (found " + suppressedCount + "): " + out);
    }

    // ── regression: ISO 8601 timestamp format ─────────────────────

    @Test
    void throwableCycleIsDetected() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a); // circular cause chain

        LogRecord record = new LogRecord(Level.SEVERE, "cycle");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setThrown(a);

        String out = formatter.format(record);
        assertTrue(out.contains("CIRCULAR"),
                "Cycle should be detected and truncated: " + out);
    }

    @Test
    void mutuallySuppressedIsDetected() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.addSuppressed(b);
        b.addSuppressed(a); // mutual suppressed

        LogRecord record = new LogRecord(Level.SEVERE, "mutual");
        record.setMillis(System.currentTimeMillis());
        record.setLoggerName("test");
        record.setThrown(a);

        String out = formatter.format(record);
        assertTrue(out.contains("CIRCULAR"),
                "Mutual suppressed should be detected: " + out);
    }

    @Test
    void timestampUsesIso8601WithTimezone() {
        LogRecord record = new LogRecord(Level.INFO, "ts test");
        record.setMillis(1719742500123L);
        record.setLoggerName("test");

        String out = formatter.format(record);
        // Must contain 'T' separator (not space)
        assertTrue(out.contains("T"), "ISO 8601 should use 'T' separator: " + out);
        // Must contain timezone offset (+HH:MM or Z)
        assertTrue(out.matches("(?s).*[+-]\\d{2}:\\d{2}.*") || out.contains("Z"),
                "ISO 8601 should include timezone offset: " + out);
    }
}
