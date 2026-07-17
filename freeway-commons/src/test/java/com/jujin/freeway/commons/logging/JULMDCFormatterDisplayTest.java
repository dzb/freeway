package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies MDC context rendering in the Freeway formatters.
 * {@code formatMDC} is private; these exercise it through the public
 * {@code format()} path, which reads {@link MDC} via the active provider.
 */
class JULMDCFormatterDisplayTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void displaysPriorityKeysFirstThenAlphabetical() {
        // Insert out of priority order to prove sorting, not insertion order.
        MDC.put("user", "alice");
        MDC.put("diagId", "abc-123");
        MDC.put("market", "SH");
        MDC.put("code", "600519");

        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertTrue(
            out.contains("[code=600519 market=SH diagId=abc-123 user=alice]"),
            "priority keys (code, market, diagId) must lead, rest alphabetical: " + out
        );
    }

    @Test
    void omitsBlockWhenMdcEmpty() {
        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertFalse(out.contains("code="), "no MDC block when context is empty: " + out);
    }

    @Test
    void omitsBlockWhenDisabled() {
        MDC.put("code", "600519");
        // package-private ctor: (useColor=false, showMDC=false)
        JULConsoleFormatter formatter = new JULConsoleFormatter(false, false);
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertFalse(out.contains("code="), "MDC block suppressed when showMDC=false: " + out);
    }

    @Test
    void fileFormatterShowsMdcWithoutColor() {
        MDC.put("code", "600519");
        MDC.put("diagId", "x");

        JULFileFormatter formatter = new JULFileFormatter();
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertTrue(out.contains("[code=600519 diagId=x]"),
            "file formatter should show MDC context: " + out);
        assertFalse(out.contains("\033["),
            "file formatter must not emit ANSI codes: " + out);
    }
}
