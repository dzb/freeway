package com.jujin.freeway.commons.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The key → environment-name spelling and the bootstrap-key channels. */
class EnvKeysTest {

    @AfterEach
    void clear() {
        System.clearProperty("freeway.probe");
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
