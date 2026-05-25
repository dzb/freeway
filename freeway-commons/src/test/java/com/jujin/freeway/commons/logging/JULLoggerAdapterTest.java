package com.jujin.freeway2.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
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
}
