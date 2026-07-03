package com.jujin.freeway.commons.logging;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Single-line JUL formatter with auto-detected ANSI colors.
 */
public final class JULConsoleFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final JULLogFormatterSupport.FormatConfig config;

    public JULConsoleFormatter() {
        this(detectColor());
    }

    JULConsoleFormatter(boolean useColor) {
        this.config = new JULLogFormatterSupport.FormatConfig(TIMESTAMP, 7, useColor, true);
    }

    public boolean useColor() {
        return config.useColor();
    }

    @Override
    public String format(LogRecord record) {
        return JULLogFormatterSupport.format(this, record, config);
    }

    /**
     * Detects whether ANSI colors should be emitted.
     */
    private static boolean detectColor() {
        if (System.getenv().containsKey("NO_COLOR") || System.getProperty("NO_COLOR") != null) {
            return false;
        }
        String override = System.getProperty("freeway.log.color", System.getenv("FREEWAY_LOG_COLOR"));
        if ("always".equalsIgnoreCase(override)) {
            return true;
        }
        if ("never".equalsIgnoreCase(override)) {
            return false;
        }
        return System.console() != null;
    }
}
