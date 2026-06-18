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
 * 2026-06-18 12:34:56.789  INFO     [main]  com.jujin.freeway.db.DbModule  -  Applied migration
 * 2026-06-18 12:34:56.790  WARNING  [worker]  com.jujin.freeway.http.WebServer  -  Slow request
 * }</pre>
 *
 * <p>Colors are enabled when the JVM has a TTY ({@link System#console()}
 * is non-null) and disabled otherwise — piping or redirecting to a file
 * automatically produces clean output. Override with
 * {@code -Dfreeway.log.color=always|never}.
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
        StringBuilder out = new StringBuilder();

        // timestamp
        out.append(dim(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis()))));

        // level — left-aligned, padded to fixed width so all lines align
        out.append(' ');
        out.append(colorLevel(padRight(record.getLevel().getName(), LEVEL_WIDTH), record.getLevel()));

        // thread
        out.append(' ');
        out.append(dim('[' + Thread.currentThread().getName() + ']'));

        // logger — FQN for audit traceability
        out.append(' ');
        String loggerName = record.getLoggerName();
        out.append(color(loggerName != null ? loggerName : "", useColor ? CYAN : null));

        // message
        out.append(' ');
        out.append(dim("- "));
        out.append(record.getMessage() != null ? record.getMessage() : "");

        // throwable
        if (record.getThrown() != null) {
            out.append('\n');
            out.append(formatThrowable(record.getThrown()));
        }

        out.append('\n');
        return out.toString();
    }

    @Override
    public String formatMessage(LogRecord record) {
        return format(record);
    }

    // ── throwable ────────────────────────────────────────────────────

    private static final String EX_INDENT = "  ";

    private String formatThrowable(Throwable thrown) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        Throwable current = thrown;
        boolean root = true;
        while (current != null) {
            if (root) {
                pw.println(color(EX_INDENT + current.toString(), useColor ? RED : null));
                root = false;
            } else {
                pw.print(color(EX_INDENT + "Caused by: ", useColor ? RED : null));
                pw.println(color(current.toString(), useColor ? RED : null));
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                pw.println(color(EX_INDENT + "    at " + frame.toString(), useColor ? DIM : null));
            }
            current = current.getCause();
        }
        pw.flush();
        return sw.toString().stripTrailing();
    }

    // ── color detection ──────────────────────────────────────────────

    /**
     * Detects whether ANSI colors should be emitted.
     *
     * <p>{@link System#console()} alone is too conservative — it returns
     * null in IDEs, some terminals, and when stdin is not a TTY even though
     * stdout still supports ANSI. The {@code TERM} fallback covers those
     * cases.
     */
    private static boolean detectColor() {
        // https://no-color.org
        if (isTruthy(System.getenv("NO_COLOR")) || isTruthy(System.getProperty("NO_COLOR"))) {
            return false;
        }
        // explicit override
        String override = System.getProperty("freeway.log.color",
                System.getenv("FREEWAY_LOG_COLOR"));
        if ("always".equalsIgnoreCase(override)) return true;
        if ("never".equalsIgnoreCase(override)) return false;

        // explicit "dumb" terminal → no color
        String term = System.getenv("TERM");
        if (term != null && term.contains("dumb")) return false;

        // Java says we have a console
        if (System.console() != null) return true;

        // TERM is set to a known color-capable value (covers IDEs, tmux, etc.)
        if (term != null && !term.isBlank()) return true;

        return false;
    }

    private static boolean isTruthy(String value) {
        return value != null && !value.isBlank();
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

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        return s + " ".repeat(n - s.length());
    }
}
