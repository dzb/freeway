package com.jujin.freeway.commons.logging;

import com.jujin.freeway.commons.util.EnvKeys;
import java.util.Locale;

import org.slf4j.LoggerFactory;

/**
 * Bootstraps SLF4J provider selection and Freeway's JUL logging enhancements
 * early in the application lifecycle.
 *
 * <p>{@link JULLoggerServiceProvider} is registered unconditionally via
 * {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}. SLF4J 2.x does
 * not prefer any particular provider among several on the classpath, so Freeway
 * makes the choice explicit: {@code freeway.log.system} selects the backend and
 * {@link #ensureProvider()} pins {@code slf4j.provider} to it before SLF4J
 * initializes. The default is {@code freeway} — Freeway's own JUL-backed
 * provider — so a transitive Logback/Log4j dependency does not silently replace
 * the framework's logging. Third-party backends stay fully usable: select one
 * explicitly ({@code logback}, {@code log4j2}, {@code simple}, or a
 * fully-qualified provider class), or use {@code auto} to keep the
 * "external provider wins, JUL as fallback" behaviour. An explicit
 * {@code -Dslf4j.provider} is always honoured and wins over the Freeway key.
 *
 * <p>Selection happens once per JVM, before SLF4J's first {@code getLogger}
 * call: {@code freeway.log.system} is read from {@code -D} or the
 * {@code FREEWAY_LOG_SYSTEM} environment variable, never from a config file
 * (files are parsed later). Both {@code FreewayApp} and {@code Freeway} call
 * {@link #ensureProvider()} from their static initializers.
 *
 * <p>After selection a one-line report names the active backend, the provider
 * class and what selected it; a provider seen on the classpath but not selected
 * is reported with the exact switch. JUL enhancement (formatters, file
 * logging) is configured only when the JUL provider is the one selected.
 */
public final class LogBootstrap {

    /** SLF4J's system property for explicitly selecting the provider. */
    static final String SLF4J_PROVIDER_PROPERTY = "slf4j.provider";

    /** The Freeway key selecting the backend; class-load time, -D/env only. */
    static final String SYSTEM_KEY = LogKeys.SYSTEM;

    /** The default: Freeway's JUL-backed provider wins over classpath order. */
    static final String SYSTEM_FREEWAY = "freeway";

    /** The opt-in to "external provider wins, JUL as fallback" (pre-2.0 default). */
    static final String SYSTEM_AUTO = "auto";

    /** The JUL provider this module registers. */
    static final String JUL_PROVIDER = JULLoggerServiceProvider.class.getName();

    /**
     * Known external SLF4J 2.x providers, in the priority {@code auto} uses
     * (logback &gt; log4j &gt; simple). Presence is probed by class name, so no
     * external dependency is needed here.
     */
    private static final String[] EXTERNAL_PROVIDER_NAMES = {
        "ch.qos.logback.classic.spi.LogbackServiceProvider", // logback-classic
        "org.apache.logging.slf4j.Log4jServiceProvider",     // log4j-slf4j2-impl
        "org.slf4j.simple.SimpleServiceProvider",            // slf4j-simple
    };

    /** Guard so provider selection runs at most once per JVM. */
    private static volatile boolean providerChecked;

    private LogBootstrap() {}

    /**
     * One provider decision: what to pin, what selected it, what else was on
     * the classpath, and whether a higher-precedence setting disagrees.
     */
    record Selection(String provider, String source, String detectedExternal, String conflict) {}

    /**
     * Detects external SLF4J providers, resolves {@code freeway.log.system},
     * pins {@code slf4j.provider} so the choice is deterministic, configures
     * JUL enhancement only for the JUL backend, and reports the outcome.
     *
     * @throws IllegalStateException when an explicitly named backend is not on
     *         the classpath — an explicit choice must not degrade silently
     */
    public static void ensureProvider() {
        if (providerChecked) {
            return;
        }
        synchronized (LogBootstrap.class) {
            if (providerChecked) {
                return;
            }
            String nativeProvider = System.getProperty(SLF4J_PROVIDER_PROPERTY);
            String systemValue = EnvKeys.bootstrap(SYSTEM_KEY);
            Selection selection;
            try {
                selection = select(systemValue, nativeProvider, contextLoader());
            } catch (IllegalStateException e) {
                // Nothing is logged yet: the fix must reach the operator.
                System.err.println("[Freeway] " + e.getMessage());
                throw e;
            }
            if (nativeProvider == null || nativeProvider.isBlank()) {
                System.setProperty(SLF4J_PROVIDER_PROPERTY, selection.provider());
            }
            providerChecked = true;
            System.err.println(startupReport(selection));
            if (selection.conflict() != null) {
                System.err.println("[Freeway] WARNING: " + selection.conflict());
            }
            if (JUL_PROVIDER.equals(selection.provider())) {
                JULEnhancer.configure();
            } else if (bridgeEnabled(EnvKeys.bootstrap(LogKeys.JUL_BRIDGE))) {
                // JDK-internal logging (HttpClient, JUL libraries) follows the
                // selected backend; without this it stays on JUL's default console.
                JULSlf4jBridge.install();
                System.err.println("[Freeway] JUL→SLF4J bridge: on (JDK logs follow "
                    + providerLabel(selection.provider()) + ")");
            }
            verifyBinding(selection);
        }
    }

    /**
     * Resolves the provider to pin without touching global state — pure so the
     * selection matrix is testable without initializing SLF4J. Package-visible
     * for tests.
     */
    static Selection select(String systemValue, String nativeProvider, ClassLoader loader) {
        String detected = detectExternal(loader);
        if (nativeProvider != null && !nativeProvider.isBlank()) {
            String provider = nativeProvider.strip();
            String conflict = null;
            if (systemValue != null && !systemValue.isBlank()) {
                String requested = resolveBackendLenient(systemValue.strip(), loader);
                if (requested != null && !requested.equals(provider)) {
                    conflict = "'" + SLF4J_PROVIDER_PROPERTY + "'=" + provider
                        + " takes precedence over 'freeway.log.system="
                        + systemValue.strip() + "' (which selects " + requested + ")";
                }
            }
            return new Selection(provider, SLF4J_PROVIDER_PROPERTY, detected, conflict);
        }
        if (systemValue == null || systemValue.isBlank()) {
            return new Selection(JUL_PROVIDER, "default", detected, null);
        }
        String system = systemValue.strip();
        return new Selection(
            resolveBackend(system, loader, true),
            "freeway.log.system=" + system,
            detected,
            null);
    }

    /**
     * One-line startup report naming the active backend; when another provider
     * is on the classpath but not selected, the next line names it and the
     * switch. Package-visible for tests.
     */
    static String startupReport(Selection selection) {
        StringBuilder report = new StringBuilder()
            .append("[Freeway] logging: ").append(providerLabel(selection.provider()))
            .append(" | provider=").append(selection.provider())
            .append(" | selected by ").append(selection.source());
        if (selection.detectedExternal() != null
                && !selection.detectedExternal().equals(selection.provider())) {
            report.append('\n')
                .append("[Freeway] ").append(providerLabel(selection.detectedExternal()))
                .append(" is on the classpath but not selected; set")
                .append(" -Dfreeway.log.system=auto (or logback/log4j2/simple) to use it");
        }
        return report.toString();
    }

    private static String providerLabel(String provider) {
        if (JUL_PROVIDER.equals(provider)) {
            return "freeway-logging (JUL)";
        }
        if (EXTERNAL_PROVIDER_NAMES[0].equals(provider)) {
            return "logback";
        }
        if (EXTERNAL_PROVIDER_NAMES[1].equals(provider)) {
            return "log4j2";
        }
        if (EXTERNAL_PROVIDER_NAMES[2].equals(provider)) {
            return "slf4j-simple";
        }
        return provider;
    }

    /** {@code freeway.log.jul-bridge}: on unless explicitly set to false. */
    static boolean bridgeEnabled(String value) {
        return value == null || !"false".equalsIgnoreCase(value.strip());
    }

    /**
     * Binds SLF4J now (with the pinned property) and compares intent against
     * the actual binding. A binding that does not match means a static logger
     * initialized SLF4J before bootstrap ran — the one situation the property
     * cannot repair — so the mismatch is reported with its fix instead of
     * leaving it silent.
     */
    private static void verifyBinding(Selection selection) {
        boolean julBound = LoggerFactory.getILoggerFactory() instanceof JULLoggerFactory;
        String warning = misbindingWarning(selection.provider(), julBound);
        if (warning == null && JUL_PROVIDER.equals(selection.provider()) && !julBound) {
            warning = "[Freeway] WARNING: freeway-logging was selected, but SLF4J already"
                + " bound " + LoggerFactory.getILoggerFactory().getClass().getName()
                + " before bootstrap ran (some static logger was created too early)."
                + " Fix: launch with -D" + SLF4J_PROVIDER_PROPERTY + "=" + JUL_PROVIDER
                + ", or create your first logger after FreewayApp/Freeway initialization.";
        }
        if (warning != null) {
            System.err.println(warning);
        }
    }

    /**
     * Returns the diagnostic for "a non-JUL provider was selected but the JUL
     * provider got bound", or {@code null} when the binding matches intent.
     * Package-visible for tests.
     */
    static String misbindingWarning(String intendedProvider, boolean julBound) {
        if (intendedProvider == null || JUL_PROVIDER.equals(intendedProvider) || !julBound) {
            return null;
        }
        return "[Freeway] WARNING: " + intendedProvider + " was selected, but SLF4J"
            + " already bound the Freeway JUL provider before bootstrap ran"
            + " (some static logger was created too early). Logging goes through"
            + " java.util.logging, not " + simpleName(intendedProvider) + ". Fix: launch"
            + " with -D" + SLF4J_PROVIDER_PROPERTY + "=" + intendedProvider + ", or create"
            + " your first logger after FreewayApp/Freeway initialization.";
    }

    private static String simpleName(String providerClass) {
        int dot = providerClass.lastIndexOf('.');
        return dot < 0 ? providerClass : providerClass.substring(0, dot);
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

    /** Detection over the given loader with the framework's own as fallback. */
    private static String detectExternal(ClassLoader loader) {
        String detected = detectExternalProvider(loader);
        if (detected == null && loader != LogBootstrap.class.getClassLoader()) {
            detected = detectExternalProvider(LogBootstrap.class.getClassLoader());
        }
        return detected;
    }

    /**
     * Maps a {@code freeway.log.system} value to a provider class. Named
     * values cover the bundled extras; anything else is a provider class name.
     */
    private static String resolveBackend(String value, ClassLoader loader, boolean strict) {
        String provider = switch (value.toLowerCase(Locale.ROOT)) {
            case SYSTEM_FREEWAY -> JUL_PROVIDER;
            case SYSTEM_AUTO -> {
                String detected = detectExternal(loader);
                yield detected != null ? detected : JUL_PROVIDER;
            }
            case "logback" -> EXTERNAL_PROVIDER_NAMES[0];
            case "log4j", "log4j2" -> EXTERNAL_PROVIDER_NAMES[1];
            case "simple" -> EXTERNAL_PROVIDER_NAMES[2];
            default -> value;
        };
        if (strict && !isLoadableOnEither(provider, loader)) {
            throw new IllegalStateException(
                "freeway.log.system='" + value + "' selects " + provider
                    + ", but that SLF4J provider is not on the classpath — add its"
                    + " dependency, or use freeway/auto");
        }
        return provider;
    }

    /** Best-effort mapping for the conflict message; unknown classes return null. */
    private static String resolveBackendLenient(String value, ClassLoader loader) {
        try {
            return resolveBackend(value, loader, false);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private static boolean isLoadableOnEither(String className, ClassLoader loader) {
        return isLoadable(className, loader)
            || isLoadable(className, LogBootstrap.class.getClassLoader());
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

    private static ClassLoader contextLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : LogBootstrap.class.getClassLoader();
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
