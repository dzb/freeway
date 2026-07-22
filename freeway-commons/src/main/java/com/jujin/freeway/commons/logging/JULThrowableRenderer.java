package com.jujin.freeway.commons.logging;

import java.util.Set;

import static com.jujin.freeway.commons.logging.JULLogFormatterSupport.RED;
import static com.jujin.freeway.commons.logging.JULLogFormatterSupport.DIM;
import static com.jujin.freeway.commons.logging.JULLogFormatterSupport.color;

/**
 * Renders {@link Throwable} traces into log output with ANSI color support.
 *
 * <p>Handles chained causes, suppressed exceptions, and circular reference
 * detection — all expressed in JUL's standard "Caused by" / "Suppressed"
 * convention. Pure stateless utility extracted from
 * {@link JULLogFormatterSupport} for cleaner separation.
 */
final class JULThrowableRenderer {

    private JULThrowableRenderer() {}

    /**
     * Appends the full trace of {@code thrown} to {@code out}.
     */
    static void appendThrowable(
        StringBuilder out,
        Throwable thrown,
        boolean useColor,
        Set<Throwable> visited
    ) {
        if (!visited.add(thrown)) return;
        Throwable current = thrown;
        boolean root = true;

        while (current != null) {
            if (root) {
                out.append(color("  " + current, useColor ? RED : null));
                root = false;
            } else {
                if (!visited.add(current)) {
                    out.append(color(
                        "  [CIRCULAR: " + current.getClass().getSimpleName() + "]",
                        useColor ? RED : null
                    ));
                    break;
                }
                out.append(color("  Caused by: ", useColor ? RED : null));
                out.append(color(String.valueOf(current), useColor ? RED : null));
            }
            out.append('\n');

            for (StackTraceElement frame : current.getStackTrace()) {
                out.append(color("      at " + frame, useColor ? DIM : null));
                out.append('\n');
            }

            for (Throwable suppressed : current.getSuppressed()) {
                appendSuppressed(out, suppressed, useColor,
                    "    Suppressed: ", "          at ", visited);
            }

            current = current.getCause();
        }
    }

    /**
     * Recursively renders a suppressed exception chain.
     */
    private static void appendSuppressed(
        StringBuilder out,
        Throwable thrown,
        boolean useColor,
        String headerPrefix,
        String framePrefix,
        Set<Throwable> visited
    ) {
        if (!visited.add(thrown)) {
            out.append(color(
                headerPrefix + thrown.getClass().getSimpleName() + " [CIRCULAR]",
                useColor ? RED : null
            ));
            out.append('\n');
            return;
        }

        out.append(color(headerPrefix + thrown, useColor ? RED : null));
        out.append('\n');

        for (StackTraceElement frame : thrown.getStackTrace()) {
            out.append(color(framePrefix + frame, useColor ? DIM : null));
            out.append('\n');
        }

        for (Throwable cause = thrown.getCause();
             cause != null;
             cause = cause.getCause()) {
            if (!visited.add(cause)) {
                out.append(color(
                    headerPrefix.replace("Suppressed:", "Caused by:")
                        + cause.getClass().getSimpleName() + " [CIRCULAR]",
                    useColor ? RED : null
                ));
                out.append('\n');
                break;
            }
            out.append(color(
                headerPrefix.replace("Suppressed:", "Caused by:") + cause,
                useColor ? RED : null
            ));
            out.append('\n');
            for (StackTraceElement frame : cause.getStackTrace()) {
                out.append(color(framePrefix + frame, useColor ? DIM : null));
                out.append('\n');
            }
        }

        for (Throwable nested : thrown.getSuppressed()) {
            appendSuppressed(out, nested, useColor,
                headerPrefix + "  ", framePrefix + "  ", visited);
        }
    }
}
