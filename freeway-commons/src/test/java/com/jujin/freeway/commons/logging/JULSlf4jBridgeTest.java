package com.jujin.freeway.commons.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.slf4j.Marker;
import org.slf4j.helpers.AbstractLogger;

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
    void resolvesResourceBundleKeysBeforeFormatting() {
        LogRecord record = new LogRecord(Level.INFO, "greeting");
        record.setResourceBundle(new GreetingBundle());
        record.setParameters(new Object[]{"bob"});
        assertEquals("hello bob", JULSlf4jBridge.formatMessage(record));
    }

    @Test
    void keepsUserConfiguredConsoleHandler() {
        Logger root = Logger.getLogger("");
        Handler[] saved = root.getHandlers();
        try {
            for (Handler handler : root.getHandlers()) {
                root.removeHandler(handler);
            }
            ConsoleHandler userConsole = new ConsoleHandler();
            userConsole.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    return "";
                }
            });
            root.addHandler(userConsole);

            JULSlf4jBridge.install();

            assertTrue(Arrays.asList(root.getHandlers()).contains(userConsole),
                "a console with a custom formatter is the user's and must survive");
            assertEquals(1, Arrays.stream(root.getHandlers())
                .filter(JULSlf4jBridge.class::isInstance).count());
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
    void mapsJulLevelsToTheSlf4jMethods() {
        RecordingLogger recorder = new RecordingLogger();
        Throwable failure = new IllegalStateException("boom");

        JULSlf4jBridge.forward(recorder, Level.SEVERE, "e", failure);
        assertEquals(org.slf4j.event.Level.ERROR, recorder.lastLevel);
        assertEquals(failure, recorder.lastThrown);

        JULSlf4jBridge.forward(recorder, Level.WARNING, "w", null);
        assertEquals(org.slf4j.event.Level.WARN, recorder.lastLevel);

        JULSlf4jBridge.forward(recorder, Level.INFO, "i", null);
        assertEquals(org.slf4j.event.Level.INFO, recorder.lastLevel);

        JULSlf4jBridge.forward(recorder, Level.CONFIG, "c", null);
        assertEquals(org.slf4j.event.Level.DEBUG, recorder.lastLevel,
            "CONFIG sits between INFO and FINE; it maps to DEBUG");

        JULSlf4jBridge.forward(recorder, Level.FINE, "d", null);
        assertEquals(org.slf4j.event.Level.DEBUG, recorder.lastLevel);

        JULSlf4jBridge.forward(recorder, Level.FINER, "t1", null);
        assertEquals(org.slf4j.event.Level.TRACE, recorder.lastLevel);

        JULSlf4jBridge.forward(recorder, Level.FINEST, "t2", null);
        assertEquals(org.slf4j.event.Level.TRACE, recorder.lastLevel);
    }

    /** A captured SLF4J backend, since the JUL provider owns this test JVM. */
    private static final class RecordingLogger extends AbstractLogger {
        private org.slf4j.event.Level lastLevel;
        private Throwable lastThrown;

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        protected void handleNormalizedLoggingCall(org.slf4j.event.Level level,
                Marker marker, String messagePattern, Object[] arguments,
                Throwable throwable) {
            this.lastLevel = level;
            this.lastThrown = throwable;
        }

        @Override
        public boolean isTraceEnabled() {
            return true;
        }

        @Override
        public boolean isDebugEnabled() {
            return true;
        }

        @Override
        public boolean isInfoEnabled() {
            return true;
        }

        @Override
        public boolean isWarnEnabled() {
            return true;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }

        @Override
        public boolean isTraceEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isDebugEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isInfoEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isWarnEnabled(Marker marker) {
            return true;
        }

        @Override
        public boolean isErrorEnabled(Marker marker) {
            return true;
        }
    }

    /** Bundle key → pattern; JUL resolves the key before MessageFormat runs. */
    private static final class GreetingBundle extends ListResourceBundle {
        @Override
        protected Object[][] getContents() {
            return new Object[][]{{"greeting", "hello {0}"}};
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
