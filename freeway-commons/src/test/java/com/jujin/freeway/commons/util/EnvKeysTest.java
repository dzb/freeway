package com.jujin.freeway.commons.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The key → environment-name spelling and the bootstrap-key channels. */
class EnvKeysTest {

    @AfterEach
    void clear() {
        System.clearProperty("freeway.probe");
        System.clearProperty("f2.probe");
        System.clearProperty("F2.probe");
    }

    @Test
    void mechanicalSpellingKeepsEveryCharacterButDots() {
        assertEquals("FREEWAY_HTTP_SERVER_PORT",
            EnvKeys.name("freeway.http.server.port"));
        // A hyphen is part of the key name, not a separator.
        assertEquals("FREEWAY_HTTP_SSL_KEY-STORE-PASSWORD",
            EnvKeys.name("freeway.http.ssl.key-store-password"));
        // A custom prefix is prepended to the key's own spelling — the cascade
        // strips it and maps the freeway.* namespace back.
        assertEquals("APP_FREEWAY_HTTP_SERVER_PORT",
            EnvKeys.name("APP_", "freeway.http.server.port"));
    }

    @Test
    void f2EnvironmentNamesMapBackToTheFrameworkNamespace() {
        assertEquals("f2.http.server.port",
            EnvKeys.fromPrefixedName(EnvKeys.DEFAULT_PREFIX, "F2_HTTP_SERVER_PORT"));
        assertEquals("f2.http.server.port",
            EnvKeys.fromPrefixedName(EnvKeys.DEFAULT_PREFIX, "f2_HTTP_SERVER_PORT"));
        assertEquals("freeway.http.server.port", EnvKeys.fromName("F2_HTTP_SERVER_PORT"));
        assertEquals("freeway.http.server.port", EnvKeys.fromName("f2_HTTP_SERVER_PORT"));
    }

    @Test
    void f2BootstrapAliasesAreAccepted() {
        System.setProperty("f2.probe", " lower ");
        assertEquals("lower", EnvKeys.bootstrap("freeway.probe"));
        System.clearProperty("f2.probe");
        System.setProperty("F2.probe", " upper ");
        assertEquals("upper", EnvKeys.bootstrap("freeway.probe"));
    }

    @Test
    void canonicalAndAliasBootstrapKeysInOneSourceAreRejected() {
        System.setProperty("freeway.probe", "canonical");
        System.setProperty("f2.probe", "alias");
        assertThrows(IllegalArgumentException.class,
            () -> EnvKeys.bootstrap("freeway.probe"));
        System.clearProperty("freeway.probe");
        System.clearProperty("f2.probe");
        System.setProperty("f2.probe", "lower");
        System.setProperty("F2.probe", "upper");
        assertThrows(IllegalArgumentException.class,
            () -> EnvKeys.bootstrap("freeway.probe"));
    }

    @Test
    void bootstrapReadsTheSystemProperty() {
        assertNull(EnvKeys.bootstrap("freeway.probe"));
        System.setProperty("freeway.probe", "  docker  ");
        assertEquals("docker", EnvKeys.bootstrap("freeway.probe"),
            "the value is stripped");
        System.setProperty("freeway.probe", "   ");
        assertNull(EnvKeys.bootstrap("freeway.probe"),
            "blank counts as unset");
    }
}
