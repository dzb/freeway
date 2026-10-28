package com.jujin.freeway.commons.logging;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Single-line JUL formatter for file output.
 */
public final class JULFileFormatter extends Formatter {

    /**
     * Built per instance, not held statically: {@code freeway.log.mdc} is read
     * at construction, and a static snapshot would freeze the first handler's
     * answer for every later file (and for tests that set the property).
     */
    private final JULLogFormatterSupport.FormatConfig config =
        new JULLogFormatterSupport.FormatConfig(
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            ).withZone(ZoneId.systemDefault()),
            8,
            false,
            false,
            JULLogFormatterSupport.showMdc()
        );

    @Override
    public String format(LogRecord record) {
        return JULLogFormatterSupport.format(this, record, config);
    }
}
