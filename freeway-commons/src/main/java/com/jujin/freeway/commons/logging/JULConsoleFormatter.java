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
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(
            ZoneId.systemDefault()
        );

    private final JULLogFormatterSupport.FormatConfig config;

    public JULConsoleFormatter() {
        this(detectColor(), detectShowMDC());
    }

    JULConsoleFormatter(boolean useColor) {
        this(useColor, detectShowMDC());
    }

    JULConsoleFormatter(boolean useColor, boolean showMDC) {
        this.config = new JULLogFormatterSupport.FormatConfig(
            TIMESTAMP,
            7,
            useColor,
            true,
            showMDC
        );
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
        if (System.getenv().containsKey("NO_COLOR")) {
            return false;
        }
        String override = System.getProperty(
            "freeway.log.color",
            System.getenv(JULEnhancer.envKeyFor("freeway.log.color"))
        );
        if ("always".equalsIgnoreCase(override) || "true".equalsIgnoreCase(override)) {
            return true;
        }
        if ("never".equalsIgnoreCase(override) || "false".equalsIgnoreCase(override)) {
            return false;
        }
        return System.console() != null;
    }

    /**
     * Detects whether MDC context should be displayed.
     * Controlled by -Dfreeway.log.mdc=true|false (default: true)
     */
    private static boolean detectShowMDC() {
        String override = System.getProperty(
            "freeway.log.mdc",
            System.getenv(JULEnhancer.envKeyFor("freeway.log.mdc"))
        );
        if (override != null) {
            return !"false".equalsIgnoreCase(override) && !"0".equals(override);
        }
        return true; // enabled by default
    }
}
