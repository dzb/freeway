package com.jujin.freeway.commons.logging;

/**
 * Activates Freeway's JUL logging enhancements (formatters, file logging)
 * early in the application lifecycle.
 *
 * <p>SLF4J provider selection is handled by SLF4J itself via
 * {@code ServiceLoader}: {@link JULLoggerServiceProvider} is registered
 * in {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}.
 * When Logback/Log4j is on the classpath, SLF4J picks it over JUL
 * automatically — no custom detection logic needed.
 *
 * <p>Call {@link #ensureProvider()} early — before any
 * {@code LoggerFactory.getLogger()} call triggers SLF4J initialization.
 * Both {@code FreewayApp} and {@code Freeway} call it from their static
 * initializers.
 */
public final class LogBootstrap {

    private LogBootstrap() {}

    /** Delegates to {@link JULEnhancer#configure()} — idempotent. */
    public static void ensureProvider() {
        JULEnhancer.configure();
    }

    /**
     * Re-applies all named file handler configurations.
     *
     * <p>Call this after the application runtime is fully started if named
     * file handlers configured via {@code freeway.log.files} are missing at
     * runtime. JUL's {@link java.util.logging.LogManager} may clear handlers
     * during its lazy initialization; this method re-attaches them.
     *
     * <p>Applies the configured named-file loggers exactly once per JVM:
     * a guard flag makes subsequent calls no-ops (re-attachment after a
     * {@code LogManager.reset()} is not performed). Files whose handler is
     * already attached to the target logger (same absolute path) are skipped
     * within that single pass, so no duplicate handlers are created.
     */
    public static void applyNamedFileLoggers() {
        JULEnhancer.applyNamedFileConfigs();
    }
}
