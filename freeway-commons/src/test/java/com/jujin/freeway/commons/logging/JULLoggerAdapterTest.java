package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class JULLoggerAdapterTest {

    @Test
    void getFullyQualifiedCallerNameReturnsAdapterFqcn() {
        Logger julLogger = Logger.getLogger("test.caller");

        JULLoggerAdapter adapter = new JULLoggerAdapter(julLogger);

        assertEquals(JULLoggerAdapter.class.getName(), adapter.getFullyQualifiedCallerName());
    }

    @Test
    void toJULLevelMapsCorrectly() {
        assertEquals(Level.FINEST, JULLoggerAdapter.toJULLevel(org.slf4j.event.Level.TRACE));
        assertEquals(Level.FINE, JULLoggerAdapter.toJULLevel(org.slf4j.event.Level.DEBUG));
        assertEquals(Level.INFO, JULLoggerAdapter.toJULLevel(org.slf4j.event.Level.INFO));
        assertEquals(Level.WARNING, JULLoggerAdapter.toJULLevel(org.slf4j.event.Level.WARN));
        assertEquals(Level.SEVERE, JULLoggerAdapter.toJULLevel(org.slf4j.event.Level.ERROR));
    }

    @Test
    void isDebugEnabledDelegatesToJULLevel() {
        Logger julLogger = Logger.getLogger("test.debug");
        julLogger.setLevel(Level.INFO);

        JULLoggerAdapter adapter = new JULLoggerAdapter(julLogger);

        assertTrue(adapter.isInfoEnabled());
        assertTrue(adapter.isWarnEnabled());
        assertTrue(adapter.isErrorEnabled());
    }

    @Test
    void markerVariantsDelegateToSameLevelCheck() {
        Logger julLogger = Logger.getLogger("test.marker");

        JULLoggerAdapter adapter = new JULLoggerAdapter(julLogger);

        assertEquals(adapter.isDebugEnabled(), adapter.isDebugEnabled(null));
        assertEquals(adapter.isInfoEnabled(), adapter.isInfoEnabled(null));
        assertEquals(adapter.isWarnEnabled(), adapter.isWarnEnabled(null));
        assertEquals(adapter.isErrorEnabled(), adapter.isErrorEnabled(null));
    }

    @Test
    void adapterNameMatchesJULLoggerName() {
        Logger julLogger = Logger.getLogger("my.custom.logger");

        JULLoggerAdapter adapter = new JULLoggerAdapter(julLogger);

        assertEquals("my.custom.logger", adapter.getName());
    }

    @Test
    void callerInfoCanBeDisabled() {
        boolean saved = JULLoggerAdapter.callerInfoEnabled;
        ArrayList<LogRecord> records = new ArrayList<>();

        Logger julLogger = Logger.getLogger("caller.off.test");
        julLogger.setUseParentHandlers(false);
        julLogger.setLevel(Level.ALL);
        Handler capturing = new Handler() {
            {
                setLevel(Level.ALL);
            }

            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        julLogger.addHandler(capturing);

        try {
            JULLoggerAdapter.callerInfoEnabled = false;
            org.slf4j.Logger slf4j =
                org.slf4j.LoggerFactory.getLogger("caller.off.test");
            slf4j.info("no caller info");

            assertEquals(1, records.size());
            LogRecord record = records.get(0);
            assertNull(
                record.getSourceClassName(),
                "Disabled caller info should leave source class null"
            );
            assertNull(
                record.getSourceMethodName(),
                "Disabled caller info should leave source method null"
            );
        } finally {
            JULLoggerAdapter.callerInfoEnabled = saved;
            julLogger.removeHandler(capturing);
        }
    }
}
