package com.jujin.freeway.cloud;

import com.jujin.freeway.cloud.internal.ConfigValues;
import com.jujin.freeway.ioc.symbol.SymbolSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed config parsing: fallbacks apply when unset, whitespace is trimmed,
 * and a malformed value fails fast with the key named in the message.
 */
class ConfigValuesTest {

    private static final SymbolSource SYMBOLS = symbolsOf(Map.of(
        "n.int", "42",
        "n.long", "3000000000",
        "n.double", "1.5",
        "n.spaced", " 8080 ",
        "n.bad", "soon"));

    @Test
    void parsesNumbersTrimsWhitespaceAndAppliesFallbacks() {
        assertEquals(42, ConfigValues.intValue(SYMBOLS, "n.int", "0"));
        assertEquals(3_000_000_000L, ConfigValues.longValue(SYMBOLS, "n.long", "0"));
        assertEquals(1.5, ConfigValues.doubleValue(SYMBOLS, "n.double", "0"));
        assertEquals(8080, ConfigValues.intValue(SYMBOLS, "n.missing", "8080"),
            "an unset key falls back to the default");
        assertEquals(8080, ConfigValues.intValue(SYMBOLS, "n.spaced", "0"),
            "surrounding whitespace is trimmed before parsing");
    }

    @Test
    void malformedValueFailsFastNamingTheKey() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> ConfigValues.intValue(SYMBOLS, "n.bad", "0"));
        assertTrue(ex.getMessage().contains("n.bad"),
            "the failing key must be named: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("soon"),
            "the rejected value must be quoted: " + ex.getMessage());
    }

    /** Minimal in-memory SymbolSource (mirrors the framework contract). */
    private static SymbolSource symbolsOf(Map<String, String> values) {
        return new SymbolSource() {
            @Override
            public String resolve(String name) {
                String value = values.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("Unknown symbol: " + name);
                }
                return value;
            }

            @Override
            public String expand(String input) {
                if (input.startsWith("${") && input.endsWith("}")) {
                    String inner = input.substring(2, input.length() - 1);
                    int colon = inner.indexOf(':');
                    String name = colon < 0 ? inner : inner.substring(0, colon);
                    String fallback = colon < 0 ? null : inner.substring(colon + 1);
                    try {
                        return resolve(name);
                    } catch (IllegalArgumentException e) {
                        return fallback;
                    }
                }
                return input;
            }
        };
    }
}
