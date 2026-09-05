package com.jujin.freeway.commons.logging;

import org.slf4j.Marker;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.lang.StackWalker.StackFrame;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * SLF4J logger implementation backed by a {@link java.util.logging.Logger}.
 *
 * <p><b>Markers:</b> SLF4J {@link Marker Markers} are ignored — JUL has no
 * marker concept, so marker-bearing calls degrade to plain level filtering
 * (see {@link #handleNormalizedLoggingCall}). Marker-aware {@code isXEnabled}
 * variants delegate to the same level check as their markerless forms.
 *
 * <p><b>Caller info:</b> source class/method are resolved with a single
 * short-circuited {@link StackWalker} walk per loggable record — the lazy
 * walker stops at the first application-code frame past the SLF4J bridge,
 * so it is bounded by bridge depth (a handful of frames), not stack depth.
 * The walk is deliberately <em>not</em> cached (e.g. by logger name): a
 * logger is typically shared by many call sites, and serving a stale call
 * point would report the wrong caller. It also runs eagerly on the logging
 * thread so records formatted later by another thread (async handlers) keep
 * correct source info. When caller info is not needed, disable the walk
 * entirely with {@code -Dfreeway.log.caller-info=false} (or
 * {@code FREEWAY_LOG_CALLER_INFO=0}).
 */
final class JULLoggerAdapter extends LegacyAbstractLogger {

    /**
     * Shared {@link StackWalker} for caller inference. Walks only until the
     * first application-code frame is found — no full-array allocation.
     */
    private static final StackWalker WALKER = StackWalker.getInstance();

    /**
     * Whether source class/method names are resolved on each log record.
     * Enabled by default; disable with
     * {@code -Dfreeway.log.caller-info=false} (or {@code FREEWAY_LOG_CALLER_INFO=0})
     * to skip the per-record stack walk when the installed formatters do not
     * display caller information.
     */
    static volatile boolean callerInfoEnabled = loadCallerInfoFlag();

    private static boolean loadCallerInfoFlag() {
        String value = System.getProperty(
            "freeway.log.caller-info",
            System.getenv(JULEnhancer.envKeyFor("freeway.log.caller-info"))
        );
        if (value != null) {
            return !("false".equalsIgnoreCase(value) || "0".equals(value));
        }
        return true;
    }

    private final Logger julLogger;

    JULLoggerAdapter(Logger julLogger) {
        this.julLogger = julLogger;
        this.name = julLogger.getName();
    }

    @Override
    protected String getFullyQualifiedCallerName() { return JULLoggerAdapter.class.getName(); }

    /**
     * Bridges a normalized SLF4J call into JUL.
     *
     * <p>The SLF4J {@code marker} is intentionally ignored: JUL has no marker
     * concept, and {@code freeway.log.*} level configuration is the only
     * filtering mechanism. Marker-aware {@code isXEnabled(Marker)} checks
     * behave identically to the markerless variants.
     */
    @Override
    protected void handleNormalizedLoggingCall(
        org.slf4j.event.Level level,
        Marker marker,
        String msg,
        Object[] args,
        Throwable throwable
    ) {
        Level julLevel = toJULLevel(level);
        if (!julLogger.isLoggable(julLevel)) {
            return;
        }

        String formatted = MessageFormatter.basicArrayFormat(msg, args);
        LogRecord record = new LogRecord(julLevel, formatted);

        // `new LogRecord(Level, String)` infers the caller from the stack.
        // From a SLF4J bridge the inferred frame is always JULLoggerAdapter
        // itself — wrong. Walk past the bridge and SLF4J internals to find
        // the actual application code frame. When caller info is disabled,
        // clear the wrong inferred values instead of walking the stack.
        if (callerInfoEnabled) {
            applyCallerInfo(record);
        } else {
            record.setSourceClassName(null);
            record.setSourceMethodName(null);
        }

        record.setLoggerName(julLogger.getName());
        if (throwable != null) {
            record.setThrown(throwable);
        }
        julLogger.log(record);
    }

    /**
     * Walks the call stack past SLF4J internals and this bridge to set the
     * actual application-code caller on the {@link LogRecord}.
     *
     * <p>Clears the inferred values first. If the walk fails (should not
     * happen in practice), {@code null} is safer than the wrong default
     * from {@code LogRecord.inferCaller()}.
     *
     * <p>Uses a shared {@link StackWalker} (lazy, no full-array allocation)
     * and stops at the first frame outside the bridge boundary.
     */
    private static void applyCallerInfo(LogRecord record) {
        // Clear first — LogRecord constructor always infers and gets it wrong
        record.setSourceClassName(null);
        record.setSourceMethodName(null);

        WALKER.walk(frames -> {
            frames.skip(1) // skip applyCallerInfo itself
                  .filter(JULLoggerAdapter::isApplicationFrame)
                  .findFirst()
                  .ifPresent(f -> {
                      record.setSourceClassName(f.getClassName());
                      record.setSourceMethodName(f.getMethodName());
                  });
            return null;
        });
    }

    private static boolean isApplicationFrame(StackFrame frame) {
        String cn = frame.getClassName();
        return !cn.startsWith("org.slf4j.") &&
               !cn.equals(JULLoggerAdapter.class.getName()) &&
               !cn.startsWith("java.util.logging.");
    }

    @Override
    public boolean isTraceEnabled() {
        return julLogger.isLoggable(Level.FINEST);
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
        return julLogger.isLoggable(Level.FINEST);
    }

    @Override
    public boolean isDebugEnabled() {
        return julLogger.isLoggable(Level.FINE);
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
        return julLogger.isLoggable(Level.FINE);
    }

    @Override
    public boolean isInfoEnabled() {
        return julLogger.isLoggable(Level.INFO);
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
        return julLogger.isLoggable(Level.INFO);
    }

    @Override
    public boolean isWarnEnabled() {
        return julLogger.isLoggable(Level.WARNING);
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
        return julLogger.isLoggable(Level.WARNING);
    }

    @Override
    public boolean isErrorEnabled() {
        return julLogger.isLoggable(Level.SEVERE);
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
        return julLogger.isLoggable(Level.SEVERE);
    }

    static Level toJULLevel(org.slf4j.event.Level slf4jLevel) {
        return switch (slf4jLevel) {
            case TRACE -> Level.FINEST;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARN -> Level.WARNING;
            case ERROR -> Level.SEVERE;
        };
    }

}
