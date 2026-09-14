package com.jujin.freeway.commons.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void displaysMdcKeysAlphabeticallyByDefault() {
        // Insert out of order to prove sorting, not insertion order. With no
        // configured priority every key sorts alphabetically: the framework
        // names no application's fields (code/market/diagId was one app's
        // vocabulary, published as if it were the framework's).
        MDC.put("user", "alice");
        MDC.put("diagId", "abc-123");
        MDC.put("market", "SH");
        MDC.put("code", "600519");

        JULConsoleFormatter formatter = new JULConsoleFormatter(false);
        LogRecord record = new LogRecord(Level.INFO, "msg");
        record.setLoggerName("test");

        String out = formatter.format(record);
        assertTrue(
            out.contains("[code=600519 diagId=abc-123 market=SH user=alice]"),
            "without a configured priority all MDC keys sort alphabetically: " + out
        );
    }

    @Test
    void mdcPriorityIsConfigurationOnly() {
        assertArrayEquals(new String[0], JULLogFormatterSupport.parseMdcPriorityKeys(null));
        assertArrayEquals(new String[0], JULLogFormatterSupport.parseMdcPriorityKeys("   "));
        assertArrayEquals(new String[]{"traceId", "requestId"},
            JULLogFormatterSupport.parseMdcPriorityKeys(" traceId , requestId "));
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
