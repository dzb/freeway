package com.jujin.freeway.commons.logging;

import org.slf4j.spi.SLF4JServiceProvider;

import java.util.ServiceLoader;

/**
 * Installs the JUL-backed SLF4J provider as a last-resort fallback,
 * yielding to any external provider (Logback, Log4j) found on the classpath.
 *
 * <p>Call {@link #ensureProvider()} early in the application lifecycle —
 * before any {@code LoggerFactory.getLogger()} call triggers SLF4J
 * initialization. Both {@code FreewayApp} and {@code Freeway} call it
 * automatically.
 *
 * <p>When an external provider (Logback, Log4j, etc.) is present, this
 * method does nothing — SLF4J will discover it naturally via
 * {@code ServiceLoader}. When no external provider is found, the JUL
 * provider is installed via reflection so that logging works out of the box
 * with zero extra dependencies.
 */
public final class LogBootstrap {

    private static volatile boolean installed;

    private LogBootstrap() {}

    /**
     * Ensures an SLF4J logging provider is available.
     *
     * <p>Idempotent — subsequent calls are no-ops.
     */
    public static void ensureProvider() {
        if (installed) {
            return;
        }
        synchronized (LogBootstrap.class) {
            if (installed) {
                return;
            }
        }

        // If ServiceLoader can find ANY SLF4JServiceProvider, an external
        // provider (Logback, Log4j, etc.) is on the classpath — yield to it.
        ServiceLoader<SLF4JServiceProvider> loader = ServiceLoader.load(SLF4JServiceProvider.class);
        if (loader.iterator().hasNext()) {
            installed = true;
            return;
        }

        // No external provider — install JUL fallback.
        installJULFallback();
        installed = true;
    }

    private static void installJULFallback() {
        JULLoggerServiceProvider jul = new JULLoggerServiceProvider();
        jul.initialize();
        try {
            Class<?> lf = org.slf4j.LoggerFactory.class;

            java.lang.reflect.Field stateField = lf.getDeclaredField("INITIALIZATION_STATE");
            stateField.setAccessible(true);
            int state = (int) stateField.get(null);

            java.lang.reflect.Field providerField = lf.getDeclaredField("PROVIDER");
            providerField.setAccessible(true);

            // SLF4J 2.x INITIALIZATION_STATE values:
            //   0 = UNINITIALIZED, 1 = ONGOING, 2 = FAILED,
            //   3 = SUCCESSFUL,     4 = NOP_FALLBACK
            if (state == 3) { // SUCCESSFUL_INITIALIZATION — already bound
                SLF4JServiceProvider existing = (SLF4JServiceProvider) providerField.get(null);
                if (existing != null && !existing.getClass().getName().contains("JUL")) {
                    providerField.set(null, jul);
                }
            } else if (state == 1) { // ONGOING_INITIALIZATION — another thread is binding
                return;
            } else {
                // UNINITIALIZED(0), FAILED(2), NOP_FALLBACK(4) — install JUL
                providerField.set(null, jul);
                stateField.set(null, 3); // SUCCESSFUL_INITIALIZATION
            }
        } catch (Exception e) {
            System.err.println("[Freeway] Failed to install JUL logging fallback: "
                    + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    }
}
