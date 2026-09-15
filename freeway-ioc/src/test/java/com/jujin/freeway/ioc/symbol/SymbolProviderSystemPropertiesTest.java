package com.jujin.freeway.ioc.symbol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The JVM system-properties tier: one definition shared by the container's
 * chain and by standalone chains, so {@code -D} precedence cannot drift
 * between the two assemblies.
 */
class SymbolProviderSystemPropertiesTest {

    private static final String KEY = "freeway.test.sys-props-tier";

    @AfterEach
    void clearProperty() {
        System.clearProperty(KEY);
    }

    @Test
    void readsTheProcessSystemProperties() {
        System.setProperty(KEY, "value");

        assertEquals("value", SymbolProvider.systemProperties().lookup(KEY));
        assertNull(SymbolProvider.systemProperties().lookup(KEY + ".absent"));
    }

    @Test
    void declaresTheSystemPropertiesTier() {
        assertEquals(SymbolProvider.TIER_SYS_PROPS, SymbolProvider.systemProperties().order());
    }

    @Test
    void returnsValuesVerbatim() {
        // A tier hands over the raw string; expanding ${...} is the chain's job.
        System.setProperty(KEY, "${other}");

        assertEquals("${other}", SymbolProvider.systemProperties().lookup(KEY));
    }
}
