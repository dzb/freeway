package com.jujin.freeway.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * The provider pin must happen before FreewayApp's first LoggerFactory use.
 * A fresh JVM is required: this JVM's SLF4J backend may already be bound.
 *
 * <p>The child classpath is prefixed with a temporary service file selecting a
 * competing provider. With the old LOG-before-static-block order SLF4J binds
 * that provider; with the fixed order the pin selects Freeway's JUL provider.</p>
 */
class FreewayAppLoggingOrderTest {

    @Test
    void providerIsPinnedBeforeFreewayAppInitializesItsLogger() throws Exception {
        Path services = Files.createTempDirectory("freeway-logging-order")
            .resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(
            services.resolve("org.slf4j.spi.SLF4JServiceProvider"),
            CompetingProvider.class.getName(),
            StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = services.getParent().getParent()
            + File.pathSeparator + System.getProperty("java.class.path");
        Process process = new ProcessBuilder(
            java,
            "-cp", classpath,
            "-Dfreeway.log.system=freeway",
            Probe.class.getName())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        assertTrue(output.contains(
                "FACTORY=com.jujin.freeway.commons.logging.JULLoggerFactory"),
            "FreewayApp must pin the JUL provider before its LOG field initializes; output: " + output);
    }

    /** Child JVM entry: loading FreewayApp must already have pinned the provider. */
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Class.forName("com.jujin.freeway.boot.FreewayApp");
            System.out.println("FACTORY=" + org.slf4j.LoggerFactory.getILoggerFactory().getClass().getName());
        }
    }

    /** A competing provider present only in the child's temporary classpath. */
    public static final class CompetingProvider implements SLF4JServiceProvider {
        private final ILoggerFactory loggerFactory = new NOPLoggerFactory();
        private final IMarkerFactory markerFactory = new BasicMarkerFactory();
        private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

        @Override public ILoggerFactory getLoggerFactory() { return loggerFactory; }
        @Override public IMarkerFactory getMarkerFactory() { return markerFactory; }
        @Override public MDCAdapter getMDCAdapter() { return mdcAdapter; }
        @Override public String getRequestedApiVersion() { return "2.0.99"; }
        @Override public void initialize() { }
    }
}
