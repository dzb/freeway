package com.jujin.freeway.commons.logging;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * SLF4J service provider backed by {@code java.util.logging}.
 *
 * <p>Registered <em>unconditionally</em> via
 * {@code META-INF/services/org.slf4j.spi.SLF4JServiceProvider}, so SLF4J's
 * {@code ServiceLoader} always sees it. It is the fallback provider: when an
 * external SLF4J 2.x provider (Logback, Log4j 2, slf4j-simple) is on the
 * classpath, {@link LogBootstrap#ensureProvider()} — invoked from
 * {@code FreewayApp}'s/{@code Freeway}'s static initializers — pins the
 * {@code slf4j.provider} system property to the external provider, so this
 * JUL provider is only actually selected when no external provider is present
 * (or the user pinned it explicitly). SLF4J picks the provider before any
 * provider code runs, so this class cannot detect that situation itself.
 *
 * <p>JUL enhancement (formatters, file logging) is handled separately by
 * {@link JULEnhancer}, which is activated when the JUL provider is selected.
 */
public final class JULLoggerServiceProvider implements SLF4JServiceProvider {

    private ILoggerFactory loggerFactory;
    private IMarkerFactory markerFactory;
    private MDCAdapter mdcAdapter;

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return "2.0.17";
    }

    @Override
    public void initialize() {
        loggerFactory = new JULLoggerFactory();
        markerFactory = new BasicMarkerFactory();
        mdcAdapter = new JULMDCAdapter();
        // Ensure JUL enhancements are active regardless of when SLF4J
        // initializes — guards against LoggerFactory.getLogger() being
        // called before Freeway's own bootstrap runs.
        JULEnhancer.configure();
    }
}
