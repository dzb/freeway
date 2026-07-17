package com.jujin.freeway.commons.logging;

import org.slf4j.Marker;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class JULLoggerAdapter extends LegacyAbstractLogger {
    private final Logger julLogger;

    JULLoggerAdapter(Logger julLogger) {
        this.julLogger = julLogger;
        this.name = julLogger.getName();
    }

    @Override
    protected String getFullyQualifiedCallerName() { return JULLoggerAdapter.class.getName(); }

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
        record.setLoggerName(julLogger.getName());
        if (throwable != null) {
            record.setThrown(throwable);
        }

        julLogger.log(record);
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
