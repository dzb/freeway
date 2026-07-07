package com.jujin.freeway.commons.logging;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Single-line JUL formatter for file output.
 */
public final class JULFileFormatter extends Formatter {

    private static final JULLogFormatterSupport.FormatConfig CONFIG =
        new JULLogFormatterSupport.FormatConfig(
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"
            ).withZone(ZoneId.systemDefault()),
            8,
            false,
            false,
            true
        );

    @Override
    public String format(LogRecord record) {
        return JULLogFormatterSupport.format(this, record, CONFIG);
    }
}
