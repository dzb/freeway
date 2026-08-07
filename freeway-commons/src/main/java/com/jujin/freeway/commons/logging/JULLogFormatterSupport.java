package com.jujin.freeway.commons.logging;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.slf4j.MDC;

final class JULLogFormatterSupport {

    static final String RESET = "\033[0m";
    static final String BOLD = "\033[1m";
    static final String DIM = "\033[2m";
    static final String RED = "\033[31m";
    static final String YELLOW = "\033[33m";
    static final String GREEN = "\033[32m";
    static final String CYAN = "\033[36m";
    static final String GRAY = "\033[90m";

    private static final ConcurrentHashMap<String, String> ABBREV_CACHE =
        new ConcurrentHashMap<>();

    /**
     * MDC keys displayed first in log output (in this order), before
     * the remaining keys in alphabetical order.
     *
     * <p>These are common correlation / routing identifiers used across
     * Freeway applications. Override by setting
     * {@code -Dfreeway.log.mdc.priority=key1,key2,...}.
     *
     * <p>Computed once at class loading time — system properties that
     * control log output formatting are not expected to change at runtime.
     */
    private static final String[] MDC_PRIORITY_KEYS = loadMdcPriorityKeys();

    /** Immutable view of {@link #MDC_PRIORITY_KEYS}, hoisted to avoid per-record allocation. */
    private static final Set<String> MDC_PRIORITY_SET = Set.of(MDC_PRIORITY_KEYS);

    private static String[] loadMdcPriorityKeys() {
        String override = System.getProperty(
            "freeway.log.mdc.priority",
            System.getenv(JULEnhancer.envKeyFor("freeway.log.mdc.priority"))
        );
        if (override != null && !override.isBlank()) {
            String[] keys = override.split(",");
            for (int i = 0; i < keys.length; i++) {
                keys[i] = keys[i].strip();
            }
            return keys;
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

    static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }

    /**
     * Formats the current thread name for log output.
     * Falls back to {@code #threadId} for unnamed virtual threads.
     */
    static String formatThread() {
        Thread t = Thread.currentThread();
        String name = t.getName();
        if (!name.isBlank()) {
            return '[' + name + ']';
        }
        return "[#" + t.threadId() + ']';
    }

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
        out.append(dim(formatThread(), color));

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
            JULThrowableRenderer.appendThrowable(out, record.getThrown(), color,
                Collections.newSetFromMap(new IdentityHashMap<>()));
        }

        out.append('\n');
        return out.toString();
    }

    private static String colorLevel(
        String text,
        Level level,
        boolean useColor
    ) {
        if (!useColor) {
            return text;
        }
        int severity = level.intValue();
        if (severity >= Level.SEVERE.intValue()) {
            return RED + BOLD + text + RESET;
        }
        if (severity >= Level.WARNING.intValue()) {
            return YELLOW + text + RESET;
        }
        if (severity >= Level.INFO.intValue()) {
            return GREEN + text + RESET;
        }
        if (severity >= Level.FINE.intValue()) {
            return GRAY + text + RESET;
        }
        return text;
    }

    static String dim(String text, boolean useColor) {
        return color(text, useColor ? DIM : null);
    }

    static String color(String text, String ansiCode) {
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
            Map<String, String> context = MDC.getCopyOfContextMap();
            if (context == null || context.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append('[');
            boolean first = true;

            // Priority keys first (in configured order)
            for (String key : MDC_PRIORITY_KEYS) {
                String value = context.get(key);
                if (value != null) {
                    if (!first) sb.append(' ');
                    sb.append(key).append('=').append(value);
                    first = false;
                }
            }

            // Other keys (alphabetically)
            var otherKeys = new TreeSet<>(context.keySet());
            otherKeys.removeAll(MDC_PRIORITY_SET);
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

}
