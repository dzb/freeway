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
        this(detectColor(), JULLogFormatterSupport.showMdc());
    }

    JULConsoleFormatter(boolean useColor) {
        this(useColor, JULLogFormatterSupport.showMdc());
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
        String override = JULEnhancer.sysOrEnv(LogKeys.COLOR);
        if ("always".equalsIgnoreCase(override) || "true".equalsIgnoreCase(override)) {
            return true;
        }
        if ("never".equalsIgnoreCase(override) || "false".equalsIgnoreCase(override)) {
            return false;
        }
        return System.console() != null;
    }

}
