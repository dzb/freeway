package com.jujin.freeway.commons.logging;

import java.util.function.Function;

/**
 * Config-value parsing for the two JUL configuration paths
 * ({@link JULEnhancer}'s cascade and {@link JULFileHandler}'s system-property
 * reader). One generic implementation — callers pass their reader, their
 * parser, and the error policy.
 */
final class LogConfig {

    private LogConfig() {}

    /**
     * Strict boolean parser: {@code Boolean::parseBoolean} never throws, so
     * garbage input would silently map to {@code false} instead of triggering
     * the lenient fallback to the default. Rejecting anything but true/false
     * makes the lenient contract real for Boolean values.
     */
    static Boolean strictBoolean(String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(
            "Invalid boolean value: '" + value + "' (expected true or false)");
    }

    /**
     * Reads a config value via {@code reader} and parses it with
     * {@code parser}, falling back to {@code defaultValue} when the value is
     * absent or blank.
     *
     * @param lenient when true, a parse error also falls back to
     *                {@code defaultValue} (the config cascade); when false the
     *                error propagates (a system-property reader fails loudly)
     */
    static <T> T propertyValue(
        String key,
        T defaultValue,
        Function<String, String> reader,
        Function<String, T> parser,
        boolean lenient
    ) {
        String raw = reader.apply(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return parser.apply(raw.strip());
        } catch (RuntimeException e) {
            if (lenient) {
                return defaultValue;
            }
            throw e;
        }
    }
}
