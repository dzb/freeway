package com.jujin.freeway.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The provider pin must happen before FreewayApp's first LoggerFactory use.
 * A fresh JVM is required: this JVM's SLF4J backend may already be bound.
 */
class FreewayAppLoggingOrderTest {

    @Test
    void providerIsPinnedBeforeFreewayAppInitializesItsLogger() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
            java,
            "-cp", System.getProperty("java.class.path"),
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
}
