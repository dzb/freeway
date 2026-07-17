package com.jujin.freeway.commons.logging;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

final class JULLogFormatterSupport {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String RED = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN = "\033[32m";
    private static final String CYAN = "\033[36m";
    private static final String GRAY = "\033[90m";

    private static final ConcurrentHashMap<String, String> ABBREV_CACHE =
        new ConcurrentHashMap<>();

    /**
     * MDC keys displayed first in log output (in this order), before
     * the remaining keys in alphabetical order.
     *
     * <p>These are common correlation / routing identifiers used across
     * Freeway applications. Override by setting
     * {@code -Dfreeway.log.mdc.priority=key1,key2,...}.
     */
    private static String[] mdcPriorityKeys() {
        String override = System.getProperty(
            "freeway.log.mdc.priority",
            System.getenv("FREEWAY_LOG_MDC_PRIORITY")
        );
        if (override != null && !override.isBlank()) {
            return override.split(",");
        }
        return new String[] { "code", "market", "diagId" };
    }

    record FormatConfig(
        DateTimeFormatter timestamp,
        int levelWidth,
        boolean useColor,
        boolean abbreviateLogger,
        boolean showMDC
    ) {}

    private JULLogFormatterSupport() {}

    static String format(
        Formatter formatter,
        LogRecord record,
        FormatConfig cfg
    ) {
        int msgLen =
            record.getMessage() != null ? record.getMessage().length() : 0;
        String loggerName = record.getLoggerName();
        int loggerLen = loggerName != null ? loggerName.length() : 0;
        StringBuilder out = new StringBuilder(80 + msgLen + loggerLen);

        boolean color = cfg.useColor();
        out.append(
            dim(
                cfg
                    .timestamp()
                    .format(Instant.ofEpochMilli(record.getMillis())),
                color
            )
        );

        out.append(' ');
        out.append(
            colorLevel(
                padRight(record.getLevel().getName(), cfg.levelWidth()),
                record.getLevel(),
                color
            )
        );

        out.append(' ');
        out.append(dim(LoggingSupport.formatThread(), color));

        out.append(' ');
        out.append(
            color(
                loggerName != null
                    ? cfg.abbreviateLogger()
                        ? abbreviate(loggerName)
                        : loggerName
                    : "",
                color ? CYAN : null
            )
        );

        // MDC context (if enabled and present)
        if (cfg.showMDC()) {
            String mdcContext = formatMDC(color);
            if (!mdcContext.isEmpty()) {
                out.append(' ');
                out.append(mdcContext);
            }
        }

        out.append(' ');
        out.append(dim("- ", color));
        out.append(formatter.formatMessage(record));

        if (record.getThrown() != null) {
            out.append('\n');
            appendThrowable(out, record.getThrown(), color, newVisitedSet());
        }

        out.append('\n');
        return out.toString();
    }

    private static String padRight(String text, int width) {
        return LoggingSupport.padRight(text, width);
    }

    private static String colorLevel(
        String text,
        java.util.logging.Level level,
        boolean useColor
    ) {
        if (!useColor) {
            return text;
        }
        int severity = level.intValue();
        if (severity >= java.util.logging.Level.SEVERE.intValue()) {
            return RED + BOLD + text + RESET;
        }
        if (severity >= java.util.logging.Level.WARNING.intValue()) {
            return YELLOW + text + RESET;
        }
        if (severity >= java.util.logging.Level.INFO.intValue()) {
            return GREEN + text + RESET;
        }
        if (severity >= java.util.logging.Level.FINE.intValue()) {
            return GRAY + text + RESET;
        }
        return text;
    }

    private static String dim(String text, boolean useColor) {
        return color(text, useColor ? DIM : null);
    }

    private static String color(String text, String ansiCode) {
        if (ansiCode == null || text == null) {
            return text;
        }
        return ansiCode + text + RESET;
    }

    private static String abbreviate(String fqn) {
        return ABBREV_CACHE.computeIfAbsent(fqn, key -> {
            String[] parts = key.split("\\.");
            if (parts.length <= 3) {
                return key;
            }
            StringBuilder sb = new StringBuilder(key.length());
            for (int i = 0; i < 3; i++) {
                String part = parts[i];
                if (part.isEmpty()) {
                    return key;
                }
                sb.append(part.charAt(0)).append('.');
            }
            for (int i = 3; i < parts.length; i++) {
                sb.append(parts[i]);
                if (i < parts.length - 1) {
                    sb.append('.');
                }
            }
            return sb.toString();
        });
    }

    /**
     * Formats MDC context as [key=value key2=value2].
     * Priority keys (configurable via freeway.log.mdc.priority) are displayed first,
     * followed by the remaining keys in alphabetical order.
     */
    private static String formatMDC(boolean useColor) {
        try {
            java.util.Map<String, String> context =
                org.slf4j.MDC.getCopyOfContextMap();
            if (context == null || context.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;

            // Priority keys first (in configured order)
            String[] priorityKeys = mdcPriorityKeys();
            for (String key : priorityKeys) {
                String value = context.get(key);
                if (value != null) {
                    if (!first) sb.append(' ');
                    sb.append(key).append('=').append(value);
                    first = false;
                }
            }

            // Other keys (alphabetically)
            java.util.Set<String> prioritySet = java.util.Set.of(priorityKeys);
            var otherKeys = new java.util.TreeSet<>(context.keySet());
            otherKeys.removeAll(prioritySet);
            for (String key : otherKeys) {
                if (!first) sb.append(' ');
                sb.append(key).append('=').append(context.get(key));
                first = false;
            }

            sb.append(']');
            return color(sb.toString(), useColor ? DIM : null);
        } catch (Exception e) {
            // MDC access failed, silently ignore
            return "";
        }
    }

    private static Set<Throwable> newVisitedSet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static void appendThrowable(
        StringBuilder out,
        Throwable thrown,
        boolean useColor,
        Set<Throwable> visited
    ) {
        if (!visited.add(thrown)) {
            return;
        }
        Throwable current = thrown;
        boolean root = true;
        while (current != null) {
            if (root) {
                out.append(color("  " + current, useColor ? RED : null));
                root = false;
            } else {
                if (!visited.add(current)) {
                    out.append(
                        color(
                            "  [CIRCULAR: " +
                                current.getClass().getSimpleName() +
                                "]",
                            useColor ? RED : null
                        )
                    );
                    break;
                }
                out.append(color("  Caused by: ", useColor ? RED : null));
                out.append(
                    color(String.valueOf(current), useColor ? RED : null)
                );
            }
            out.append('\n');
            for (StackTraceElement frame : current.getStackTrace()) {
                out.append(color("      at " + frame, useColor ? DIM : null));
                out.append('\n');
            }
            for (Throwable suppressed : current.getSuppressed()) {
                appendSuppressed(
                    out,
                    suppressed,
                    useColor,
                    "    Suppressed: ",
                    "          at ",
                    visited
                );
            }
            current = current.getCause();
        }
    }

    private static void appendSuppressed(
        StringBuilder out,
        Throwable thrown,
        boolean useColor,
        String headerPrefix,
        String framePrefix,
        Set<Throwable> visited
    ) {
        if (!visited.add(thrown)) {
            out.append(
                color(
                    headerPrefix +
                        thrown.getClass().getSimpleName() +
                        " [CIRCULAR]",
                    useColor ? RED : null
                )
            );
            out.append('\n');
            return;
        }
        out.append(color(headerPrefix + thrown, useColor ? RED : null));
        out.append('\n');
        for (StackTraceElement frame : thrown.getStackTrace()) {
            out.append(color(framePrefix + frame, useColor ? DIM : null));
            out.append('\n');
        }
        for (
            Throwable cause = thrown.getCause();
            cause != null;
            cause = cause.getCause()
        ) {
            if (!visited.add(cause)) {
                out.append(
                    color(
                        headerPrefix.replace("Suppressed:", "Caused by:") +
                            cause.getClass().getSimpleName() +
                            " [CIRCULAR]",
                        useColor ? RED : null
                    )
                );
                out.append('\n');
                break;
            }
            out.append(
                color(
                    headerPrefix.replace("Suppressed:", "Caused by:") + cause,
                    useColor ? RED : null
                )
            );
            out.append('\n');
            for (StackTraceElement frame : cause.getStackTrace()) {
                out.append(color(framePrefix + frame, useColor ? DIM : null));
                out.append('\n');
            }
        }
        for (Throwable nested : thrown.getSuppressed()) {
            appendSuppressed(
                out,
                nested,
                useColor,
                headerPrefix + "  ",
                framePrefix + "  ",
                visited
            );
        }
    }
}
