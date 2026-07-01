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
 * Single-line JUL formatter for file output — no ANSI colors, ISO 8601
 * timestamps, full logger names.
 *
 * <p>Format:
 * <pre>{@code
 * 2026-06-30T18:15:00.123+08:00  INFO     [main] com.jujin.freeway.db.DbModule - Applied migration v3
 * 2026-06-30T18:15:00.456  WARNING  [worker] com.jujin.freeway.http.WebServer - Slow request
 * 2026-06-30T18:15:01.789  SEVERE   [main] com.example.Service - Connection failed
 *   java.net.ConnectException: Connection refused
 *       at com.example.Service.connect(Service.java:42)
 *       at ...
 *   Caused by: java.net.SocketException: ...
 *       at ...
 * }</pre>
 *
 * <p>Design follows industry best practices (Logback, Log4j2, Spring Boot):
 * <ul>
 *   <li>ISO 8601 timestamp with timezone offset — machine-parseable</li>
 *   <li>Full (unabbreviated) logger name — grep/Splunk/ELK friendly</li>
 *   <li>Level left-aligned at 8 chars — accommodates {@code WARNING}</li>
 *   <li>Full exception chain with {@code Caused by:} and indented frames</li>
 *   <li>No ANSI color codes — safe for file output and log aggregators</li>
 * </ul>
 */
public final class JULFileFormatter extends Formatter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                    .withZone(ZoneId.systemDefault());

    private static final int LEVEL_WIDTH = 8;

    @Override
    public String format(LogRecord record) {
        int msgLen = record.getMessage() != null ? record.getMessage().length() : 0;
        String loggerName = record.getLoggerName();
        int loggerLen = loggerName != null ? loggerName.length() : 0;
        StringBuilder out = new StringBuilder(80 + msgLen + loggerLen);

        // timestamp — ISO 8601 with timezone (TIMESTAMP already has .withZone())
        out.append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())));

        // level — left-aligned, fixed width
        out.append(' ');
        out.append(LoggingSupport.padRight(record.getLevel().getName(), LEVEL_WIDTH));

        // thread
        out.append(' ');
        out.append(LoggingSupport.formatThread());

        // logger — full name
        out.append(' ');
        out.append(loggerName != null ? loggerName : "");

        // message
        out.append(" - ");
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

    private static String formatThrowable(Throwable thrown) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        formatThrowable(thrown, pw, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        pw.flush();
        return sw.toString().stripTrailing();
    }

    private static void formatThrowable(Throwable t, PrintWriter pw,
                                        java.util.Set<Throwable> visited) {
        if (!visited.add(t)) return;
        Throwable current = t;
        boolean root = true;
        while (current != null) {
            if (root) {
                pw.print("  ");
                pw.println(current);
                root = false;
            } else {
                if (!visited.add(current)) {
                    pw.print("  [CIRCULAR: ");
                    pw.print(current.getClass().getSimpleName());
                    pw.println("]");
                    break;
                }
                pw.print("  Caused by: ");
                pw.println(current);
            }
            for (StackTraceElement frame : current.getStackTrace()) {
                pw.print("      at ");
                pw.println(frame);
            }
            for (Throwable suppressed : current.getSuppressed()) {
                renderSuppressed(suppressed, pw, "    Suppressed: ",
                        "          at ", visited);
            }
            current = current.getCause();
        }
    }

    private static void renderSuppressed(Throwable t, PrintWriter pw,
                                         String headerPrefix, String framePrefix,
                                         java.util.Set<Throwable> visited) {
        if (!visited.add(t)) {
            pw.print(headerPrefix);
            pw.println(t.getClass().getSimpleName() + " [CIRCULAR]");
            return;
        }
        pw.print(headerPrefix);
        pw.println(t);
        for (StackTraceElement frame : t.getStackTrace()) {
            pw.print(framePrefix);
            pw.println(frame);
        }
        for (Throwable cause = t.getCause(); cause != null; cause = cause.getCause()) {
            if (!visited.add(cause)) {
                pw.print(headerPrefix.replace("Suppressed:", "Caused by:"));
                pw.println(cause.getClass().getSimpleName() + " [CIRCULAR]");
                break;
            }
            pw.print(headerPrefix.replace("Suppressed:", "Caused by:"));
            pw.println(cause);
            for (StackTraceElement frame : cause.getStackTrace()) {
                pw.print(framePrefix);
                pw.println(frame);
            }
        }
        for (Throwable nested : t.getSuppressed()) {
            renderSuppressed(nested, pw, headerPrefix + "  ", framePrefix + "  ", visited);
        }
    }
}
