package com.jujin.freeway.cloud.internal;

import com.jujin.freeway.ioc.symbol.SymbolSource;

import java.util.function.Function;

/**
 * Typed config parsing with the key named in every failure — a malformed
 * numeric value surfaces as {@code freeway.cloud.rpc.connect-timeout must be
 * an integer: 'soon'} instead of a bare {@code NumberFormatException} with no
 * context. Startup still fails fast; the message just says where.
 */
public final class ConfigValues {

    private ConfigValues() {}

    /** Resolves {@code key} as an int, falling back to {@code fallback} when unset. */
    public static int intValue(SymbolSource symbols, String key, String fallback) {
        return parse(symbols, key, fallback, "an integer", Integer::parseInt);
    }

    /** Resolves {@code key} as a long, falling back to {@code fallback} when unset. */
    public static long longValue(SymbolSource symbols, String key, String fallback) {
        return parse(symbols, key, fallback, "a long", Long::parseLong);
    }

    /** Resolves {@code key} as a double, falling back to {@code fallback} when unset. */
    public static double doubleValue(SymbolSource symbols, String key, String fallback) {
        return parse(symbols, key, fallback, "a number", Double::parseDouble);
    }

    private static <T> T parse(
        SymbolSource symbols, String key, String fallback,
        String expected, Function<String, T> parser
    ) {
        String raw = symbols.resolve(key, fallback).trim();
        try {
            return parser.apply(raw);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                key + " must be " + expected + ": '" + raw + "'");
        }
    }
}
