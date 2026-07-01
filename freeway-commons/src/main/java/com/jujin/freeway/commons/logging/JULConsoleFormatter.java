package com.jujin.freeway.commons.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Single-line JUL formatter with auto-detected ANSI colors.
 *
 * <p>Format — level column left-aligned at {@value #LEVEL_WIDTH} chars,
 * full timestamp and FQN logger for audit trails:
 * <pre>{@code
 * 2026-06-18 12:34:56.789  INFO     [main]  c.j.f.db.DbModule  -  Applied migration
 * 2026-06-18 12:34:56.790  WARNING  [worker]  c.j.f.http.WebServer  -  Slow request
 * }</pre>
 *
 * <p>Colors are enabled when the JVM has an attached console
 * ({@link System#console()} is non-null) and disabled otherwise —
 * piping or redirecting to a file automatically produces clean output.
 * Override with {@code -Dfreeway.log.color=always|never}.
 *
 * <p>Opt out entirely with {@code -Dfreeway.log.format=simple} to keep
 * JUL's default {@link java.util.logging.SimpleFormatter}.
 */
public final class JULConsoleFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final String RESET = "\033[0m";
    private static final String BOLD  = "\033[1m";
    private static final String DIM   = "\033[2m";
    private static final String RED   = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String GREEN  = "\033[32m";
    private static final String CYAN   = "\033[36m";
    private static final String GRAY   = "\033[90m";

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

    private static final int LEVEL_WIDTH = 7; // widest JUL level: "WARNING"

    @Override
    public String format(LogRecord record) {
        int msgLen = record.getMessage() != null ? record.getMessage().length() : 0;
        String loggerName = record.getLoggerName();
        int loggerLen = loggerName != null ? loggerName.length() : 0;
        StringBuilder out = new StringBuilder(80 + msgLen + loggerLen);

        // timestamp
        out.append(dim(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis()))));

        // level — left-aligned, padded to fixed width so all lines align
        out.append(' ');
        out.append(colorLevel(LoggingSupport.padRight(record.getLevel().getName(), LEVEL_WIDTH), record.getLevel()));

        // thread
        out.append(' ');
        out.append(dim(LoggingSupport.formatThread()));

        // logger — abbreviated package, full class name
        out.append(' ');
        out.append(color(loggerName != null ? abbreviate(loggerName) : "", useColor ? CYAN : null));

        // message
        out.append(' ');
        out.append(dim("- "));
        out.append(super.formatMessage(record));

        // throwable
        if (record.getThrown() != null) {
            out.append('\n');
            out.append(formatThrowable(record.getThrown()));
        }

        out.append('\n');
        return out.toString();
    }

    // ── throwable ────────────────────────────────────────────────────

    private static final String EX_INDENT = "  ";

    private String formatThrowable(Throwable thrown) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        formatThrowable(thrown, pw, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        pw.flush();
        return sw.toString().stripTrailing();
    }

    private void formatThrowable(Throwable t, PrintWriter pw,
                                  java.util.Set<Throwable> visited) {
        if (!visited.add(t)) return;
        Throwable current = t;
        boolean root = true;
        while (current != null) {
            if (root) {
                pw.println(color(EX_INDENT + current.toString(), useColor ? RED : null));
                root = false;
            } else {
                if (!visited.add(current)) {
                    pw.println(color(EX_INDENT + "Caused by: " + current.getClass().getSimpleName() + " [CIRCULAR]", useColor ? RED : null));
                    break;
                }
                pw.print(color(EX_INDENT + "Caused by: ", useColor ? RED : null));
                pw.println(color(current.toString(), useColor ? RED : null));
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                pw.println(color(EX_INDENT + "    at " + frame.toString(), useColor ? DIM : null));
            }
            for (Throwable suppressed : current.getSuppressed()) {
                renderSuppressed(suppressed, pw, EX_INDENT + "  Suppressed: ",
                        EX_INDENT + "      at ", visited);
            }
            current = current.getCause();
        }
    }

    private void renderSuppressed(Throwable t, PrintWriter pw,
                                   String headerPrefix, String framePrefix,
                                   java.util.Set<Throwable> visited) {
        if (!visited.add(t)) {
            pw.println(color(headerPrefix + t.getClass().getSimpleName() + " [CIRCULAR]", useColor ? RED : null));
            return;
        }
        pw.println(color(headerPrefix + t, useColor ? RED : null));
        for (StackTraceElement frame : t.getStackTrace()) {
            pw.println(color(framePrefix + frame, useColor ? DIM : null));
        }
        for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
            if (!visited.add(cause)) {
                pw.println(color(headerPrefix.replace("Suppressed:", "Caused by:") + cause.getClass().getSimpleName() + " [CIRCULAR]", useColor ? RED : null));
                break;
            }
            pw.println(color(headerPrefix.replace("Suppressed:", "Caused by:")
                    + cause, useColor ? RED : null));
            for (StackTraceElement frame : cause.getStackTrace()) {
                pw.println(color(framePrefix + frame, useColor ? DIM : null));
            }
        }
        for (Throwable nested : t.getSuppressed()) {
            renderSuppressed(nested, pw, headerPrefix + "  ", framePrefix + "  ", visited);
        }
    }

    // ── color detection ──────────────────────────────────────────────

    /**
     * Detects whether ANSI colors should be emitted.
     *
     * <p>This uses the JVM console as the only automatic signal. That keeps
     * the check cheap and avoids heuristics that can enable color in redirected
     * output or other non-interactive environments.
     */
    private static boolean detectColor() {
        // https://no-color.org — NO_COLOR disables color when SET (any value, including empty)
        if (System.getenv().containsKey("NO_COLOR")
                || System.getProperty("NO_COLOR") != null) {
            return false;
        }
        // explicit override
        String override = System.getProperty("freeway.log.color",
                System.getenv("FREEWAY_LOG_COLOR"));
        if ("always".equalsIgnoreCase(override)) return true;
        if ("never".equalsIgnoreCase(override)) return false;

        return System.console() != null;
    }

    // ── color helpers ────────────────────────────────────────────────

    private String colorLevel(String text, Level level) {
        if (!useColor) return text;
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

    private String dim(String text) {
        return color(text, useColor ? DIM : null);
    }

    private String color(String text, String ansiCode) {
        if (ansiCode == null || text == null) return text;
        return ansiCode + text + RESET;
    }

    // ── utilities ────────────────────────────────────────────────────

    private static final java.util.concurrent.ConcurrentHashMap<String, String> ABBREV_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Abbreviate first 3 segments (groupId) to first letter, keep the rest intact. */
    private static String abbreviate(String fqn) {
        return ABBREV_CACHE.computeIfAbsent(fqn, k -> {
            String[] parts = k.split("\\.");
            if (parts.length <= 3) return k;
            StringBuilder sb = new StringBuilder(k.length());
            for (int i = 0; i < 3; i++) {
                String p = parts[i];
                if (p.isEmpty()) return k; // consecutive dots → use full name
                sb.append(p.charAt(0)).append('.');
            }
            for (int i = 3; i < parts.length; i++) {
                sb.append(parts[i]);
                if (i < parts.length - 1) sb.append('.');
            }
            return sb.toString();
        });
    }

}
