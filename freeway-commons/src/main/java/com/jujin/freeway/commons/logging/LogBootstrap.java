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
     * <p>Safe to call at any time and multiple times — handlers are not
     * deduplicated, so typical usage is once after {@code FreewayApp.run()}.
     */
    public static void applyNamedFileLoggers() {
        JULEnhancer.applyNamedFileConfigs();
    }
}
