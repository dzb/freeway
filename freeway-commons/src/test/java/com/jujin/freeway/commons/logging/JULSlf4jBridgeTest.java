package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/**
 * The JUL-to-SLF4J bridge used when an external backend is selected: it
 * resolves JUL's message contract and replaces the JDK's default console
 * handler exactly once.
 */
class JULSlf4jBridgeTest {

    @Test
    void resolvesJulMessageParametersAndBundleKeys() {
        LogRecord placeholders = new LogRecord(Level.INFO, "user {0} did {1}");
        placeholders.setParameters(new Object[]{"alice", "login"});
        assertEquals("user alice did login", JULSlf4jBridge.formatMessage(placeholders));

        LogRecord plain = new LogRecord(Level.INFO, "no parameters");
        assertEquals("no parameters", JULSlf4jBridge.formatMessage(plain));

        LogRecord malformed = new LogRecord(Level.INFO, "brace {");
        malformed.setParameters(new Object[]{"x"});
        assertEquals("brace {", JULSlf4jBridge.formatMessage(malformed),
            "a malformed pattern keeps the raw message instead of failing the log call");

        LogRecord nullMessage = new LogRecord(Level.INFO, null);
        assertEquals("", JULSlf4jBridge.formatMessage(nullMessage));
    }

    @Test
    void installReplacesTheDefaultConsoleAndIsIdempotent() {
        Logger root = Logger.getLogger("");
        Handler[] saved = root.getHandlers();
        try {
            JULSlf4jBridge.install();
            JULSlf4jBridge.install();

            List<Handler> handlers = new ArrayList<>(List.of(root.getHandlers()));
            assertEquals(1, handlers.stream().filter(JULSlf4jBridge.class::isInstance).count(),
                "one bridge, however often install runs");
            assertFalse(handlers.stream().anyMatch(
                    h -> h instanceof java.util.logging.ConsoleHandler
                        && h.getFormatter() instanceof java.util.logging.SimpleFormatter),
                "the JDK's untouched default console is replaced so records are not doubled");
            assertTrue(JULSlf4jBridge.isInstalled());
        } finally {
            for (Handler handler : root.getHandlers()) {
                root.removeHandler(handler);
            }
            for (Handler handler : saved) {
                root.addHandler(handler);
            }
            JULSlf4jBridge.resetForTest();
        }
    }

    @Test
    void bridgeCanBeDisabledByTheKey() {
        assertTrue(LogBootstrap.bridgeEnabled(null));
        assertTrue(LogBootstrap.bridgeEnabled("true"));
        assertTrue(LogBootstrap.bridgeEnabled("anything"));
        assertFalse(LogBootstrap.bridgeEnabled("false"));
        assertFalse(LogBootstrap.bridgeEnabled("FALSE"));
    }
}
