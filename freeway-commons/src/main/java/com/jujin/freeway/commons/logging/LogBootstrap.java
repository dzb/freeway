package com.jujin.freeway.commons.logging;

/**
 * Bootstraps SLF4J provider selection and Freeway's JUL logging enhancements
 * early in the application lifecycle.
 *
 * <p>{@link JULLoggerServiceProvider} is registered <em>unconditionally</em>
 * via {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}, so SLF4J's
 * {@code ServiceLoader} always sees it. SLF4J 2.x does not prefer any
 * particular provider: with several on the classpath it only warns and keeps
 * the first one in {@code ServiceLoader} (classpath) order. That is why
 * {@link #ensureProvider()} must run before SLF4J initializes: it probes the
 * classpath for external SLF4J 2.x providers (Logback, Log4j 2, slf4j-simple)
 * and, when one is present, pins the {@code slf4j.provider} system property
 * to that provider (fixed priority: logback &gt; log4j &gt; simple), so the
 * external provider deterministically wins over the JUL fallback. A
 * user-supplied {@code -Dslf4j.provider} is always respected and never
 * overridden. When no external provider is present (or the user pinned the
 * JUL provider explicitly), the JUL provider takes effect and
 * {@link JULEnhancer} configures console/file logging.
 *
 * <p>Call {@link #ensureProvider()} early — before any
 * {@code LoggerFactory.getLogger()} call triggers SLF4J initialization.
 * Both {@code FreewayApp} and {@code Freeway} call it from their static
 * initializers.
 *
 * <p><b>Limitation:</b> SLF4J selects its provider when it first initializes,
 * before any provider code runs, so the provider itself cannot self-check. If
 * application code calls {@code LoggerFactory.getLogger()} before
 * {@code FreewayApp}/{@code Freeway} is loaded, {@link #ensureProvider()} has
 * not run yet and SLF4J falls back to {@code ServiceLoader} classpath order.
 */
public final class LogBootstrap {

    /** SLF4J's system property for explicitly selecting the provider. */
    static final String SLF4J_PROVIDER_PROPERTY = "slf4j.provider";

    /**
     * Known external SLF4J 2.x providers, in fixed selection priority
     * (logback &gt; log4j &gt; simple). Presence is probed by class name, so
     * no external dependency is needed here.
     */
    private static final String[] EXTERNAL_PROVIDER_NAMES = {
        "ch.qos.logback.classic.spi.LogbackServiceProvider", // logback-classic
        "org.apache.logging.slf4j.Log4jServiceProvider",     // log4j-slf4j2-impl
        "org.slf4j.simple.SimpleServiceProvider",            // slf4j-simple
    };

    /** Guard so the classpath probe runs at most once per JVM. */
    private static volatile boolean providerChecked;

    private LogBootstrap() {}

    /**
     * Detects external SLF4J providers and, when one is present, pins
     * {@code slf4j.provider} so provider selection is deterministic; JUL
     * enhancement is configured only when the JUL provider will actually be
     * active. Idempotent and safe for concurrent first calls.
     */
    public static void ensureProvider() {
        if (providerChecked) {
            return;
        }
        synchronized (LogBootstrap.class) {
            if (providerChecked) {
                return;
            }
            providerChecked = true;

            String userProvider = System.getProperty(SLF4J_PROVIDER_PROPERTY);
            if (userProvider != null && !userProvider.isBlank()) {
                // An explicit user choice always wins — never override it.
                // JUL enhancement only makes sense when JUL is the provider.
                if (isJulProvider(userProvider)) {
                    JULEnhancer.configure();
                }
                return;
            }

            String external = applyProviderSelection(
                Thread.currentThread().getContextClassLoader()
            );
            if (external == null) {
                external = applyProviderSelection(
                    LogBootstrap.class.getClassLoader()
                );
            }
            if (external != null) {
                reportSelection(external);
                // The external provider owns logging — JUL console/file
                // handlers would never be read; skip them.
                return;
            }
            JULEnhancer.configure();
        }
    }

    /**
     * Returns the highest-priority external SLF4J provider class name that is
     * loadable via {@code loader}, or {@code null} when none is present.
     * Package-visible for tests: a custom {@link ClassLoader} can fake the
     * provider classes without adding external dependencies.
     */
    static String detectExternalProvider(ClassLoader loader) {
        for (String name : EXTERNAL_PROVIDER_NAMES) {
            if (isLoadable(name, loader)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Pins {@code slf4j.provider} to the detected external provider for
     * {@code loader}, unless the user has already set the property
     * explicitly — an explicit value is never overridden. Returns the pinned
     * class name, or {@code null} when nothing was pinned. Package-visible
     * for tests.
     */
    static String applyProviderSelection(ClassLoader loader) {
        String userProvider = System.getProperty(SLF4J_PROVIDER_PROPERTY);
        if (userProvider != null && !userProvider.isBlank()) {
            return null;
        }
        String external = detectExternalProvider(loader);
        if (external != null) {
            System.setProperty(SLF4J_PROVIDER_PROPERTY, external);
        }
        return external;
    }

    /** Probes for a class without initializing or linking side effects. */
    private static boolean isLoadable(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean isJulProvider(String provider) {
        return JULLoggerServiceProvider.class.getName().equals(provider);
    }

    /** Emits a pre-SLF4J diagnostic to stderr (SLF4J is not usable yet). */
    private static void reportSelection(String provider) {
        System.err.println(
            "[Freeway] Detected external SLF4J provider " + provider
                + " — pinning '" + SLF4J_PROVIDER_PROPERTY + "' so it wins over "
                + "the JUL fallback (fixed priority: logback > log4j > simple; "
                + "set -Dslf4j.provider to override)."
        );
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
