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
}
