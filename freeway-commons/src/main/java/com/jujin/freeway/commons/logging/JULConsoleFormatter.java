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
    private static final int LEVEL_WIDTH = 7;

    private final boolean useColor;

    public JULConsoleFormatter() {
        this(detectColor());
    }

    JULConsoleFormatter(boolean useColor) {
        this.useColor = useColor;
    }

    public boolean useColor() {
        return useColor;
    }

    @Override
    public String format(LogRecord record) {
        return JULLogFormatterSupport.format(
            this,
            record,
            TIMESTAMP,
            LEVEL_WIDTH,
            useColor,
            true
        );
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
