package com.jujin.freeway.commons.logging;

/**
 * Shared formatting utilities used by the JUL formatter implementations.
 */
final class LoggingSupport {

    private LoggingSupport() {}

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
}
