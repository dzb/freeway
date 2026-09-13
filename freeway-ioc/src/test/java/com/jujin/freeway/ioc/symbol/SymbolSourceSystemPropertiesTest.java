package com.jujin.freeway.ioc.symbol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The standalone source used by adapters that also have a container path:
 * system properties only, absent symbols throwing on the strict accessor.
 */
class SymbolSourceSystemPropertiesTest {

    private static final String KEY = "freeway.test.system-properties";

    @AfterEach
    void clearProperty() {
        System.clearProperty(KEY);
    }

    @Test
    void resolvesFromSystemProperties() {
        System.setProperty(KEY, "value");
        SymbolSource symbols = SymbolSource.systemProperties();

        assertEquals("value", symbols.resolve(KEY));
        assertEquals("value", symbols.resolve(KEY, "fallback"));
        assertEquals("${" + KEY + "}", symbols.expand("${" + KEY + "}"));
    }

    @Test
    void absentSymbolThrowsOnStrictResolveAndFallsBackOnLenientResolve() {
        SymbolSource symbols = SymbolSource.systemProperties();

        UnknownSymbolException error =
            assertThrows(UnknownSymbolException.class, () -> symbols.resolve(KEY));
        assertEquals("Unknown symbol: " + KEY, error.getMessage());
        assertEquals("fallback", symbols.resolve(KEY, "fallback"));
        assertNull(symbols.resolve(KEY, null));
    }
}
