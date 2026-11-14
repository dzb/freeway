package com.jujin.freeway.commons.logging;

import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards {@code java.util.logging} records to SLF4J so JDK-internal logging
 * (HttpClient, JUL-based libraries) reaches the backend the application chose,
 * instead of the JUL default console.
 *
 * <p>Installed only when an external SLF4J provider is selected: with Freeway's
 * own JUL provider the records are already where they belong. The bridge
 * replaces the JDK's untouched default {@link ConsoleHandler} on the root
 * logger so a record is not printed twice; user-configured JUL handlers are
 * left alone (they may be intentional). {@code freeway.log.jul-bridge=false}
 * disables the install entirely.
 *
 * <p>Records carry JUL's {@code MessageFormat} parameters and resource bundle;
 * both are resolved before forwarding. A per-thread flag stops a backend that
 * itself logs through JUL from looping back into the bridge.
 */
final class JULSlf4jBridge extends Handler {

    private static final ThreadLocal<Boolean> FORWARDING = ThreadLocal.withInitial(() -> false);

    private static volatile boolean installed;
    private static volatile JULSlf4jBridge handler;

    private JULSlf4jBridge() {
        setLevel(Level.ALL);
    }

    /** True once the bridge handler is attached to the JUL root logger. */
    static boolean isInstalled() {
        return installed;
    }

    /**
     * Attaches the bridge once per JVM, replacing the JDK's default console
     * handler so JUL records are not delivered twice.
     */
    static synchronized void install() {
        if (installed) {
            return;
        }
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        for (Handler existing : root.getHandlers()) {
            if (isJdkDefaultConsole(existing)) {
                root.removeHandler(existing);
            }
        }
        JULSlf4jBridge bridge = new JULSlf4jBridge();
        root.addHandler(bridge);
        handler = bridge;
        installed = true;
    }

    /** Detaches the bridge (tests restore JUL global state with this). */
    static synchronized void resetForTest() {
        JULSlf4jBridge current = handler;
        handler = null;
        installed = false;
        if (current != null) {
            java.util.logging.Logger.getLogger("").removeHandler(current);
        }
    }

    private static boolean isJdkDefaultConsole(Handler handler) {
        return handler instanceof ConsoleHandler
            && handler.getFormatter() instanceof SimpleFormatter;
    }

    @Override
    public void publish(LogRecord record) {
        if (record == null || FORWARDING.get() || !isLoggable(record)) {
            return;
        }
        String loggerName = record.getLoggerName();
        Logger logger = LoggerFactory.getLogger(
            loggerName == null || loggerName.isBlank() ? "jul" : loggerName);
        String message = formatMessage(record);
        Throwable thrown = record.getThrown();
        FORWARDING.set(true);
        try {
            forward(logger, record.getLevel(), message, thrown);
        } catch (RuntimeException e) {
            // A broken backend must not break the JUL caller.
        } finally {
            FORWARDING.set(false);
        }
    }

    private static void forward(Logger logger, Level level, String message, Throwable thrown) {
        int severity = level.intValue();
        if (severity >= Level.SEVERE.intValue()) {
            logger.error(message, thrown);
        } else if (severity >= Level.WARNING.intValue()) {
            logger.warn(message, thrown);
        } else if (severity >= Level.INFO.intValue()) {
            logger.info(message, thrown);
        } else if (severity >= Level.FINE.intValue()) {
            logger.debug(message, thrown);
        } else {
            logger.trace(message, thrown);
        }
    }

    /**
     * JUL's message contract: a resource bundle may translate the key, then
     * {@link MessageFormat} fills {@code {0}}-style parameters. Neither is
     * applied by SLF4J's {@code {}} formatter, so the bridge resolves them.
     */
    static String formatMessage(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        ResourceBundle bundle = record.getResourceBundle();
        if (bundle != null) {
            try {
                message = bundle.getString(message);
            } catch (RuntimeException ignored) {
                // an unknown key keeps the raw message, like JUL's Formatter
            }
        }
        Object[] parameters = record.getParameters();
        if (parameters == null || parameters.length == 0) {
            return message;
        }
        try {
            return MessageFormat.format(message, parameters);
        } catch (IllegalArgumentException malformed) {
            return message;
        }
    }

    @Override
    public void flush() {
        // SLF4J has no flush; the backend's own lifecycle owns it.
    }

    @Override
    public void close() throws SecurityException {
        // Detached by resetForTest; nothing backend-owned to close.
    }
}
