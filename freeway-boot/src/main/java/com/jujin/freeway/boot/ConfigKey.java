package com.jujin.freeway.boot;

import java.util.function.Function;

/**
 * A typed configuration key: key name, target type, default value, and a
 * parser from the raw string. Pairs with {@link AppConfig#get(ConfigKey)}
 * for type-safe config access with centralized parsing and defaults —
 * the alternative to scattered {@code Integer.parseInt(...)} at each use
 * site with inconsistent error messages.
 *
 * <p>Example:
 * <pre>{@code
 * public static final ConfigKey<Integer> HTTP_PORT =
 *     ConfigKey.of("server.port", Integer.class, 8080, Integer::parseInt);
 *
 * int port = config.get(HTTP_PORT);   // typed, defaulted, parsed once
 * }</pre>
 *
 * @param <T> the value type
 */
public record ConfigKey<T>(
    String key,
    Class<T> type,
    T defaultValue,
    Function<String, T> parser
) {

    /**
     * Creates a key; the parser must handle the raw string and throw
     * {@link IllegalArgumentException} with a clear message on bad input.
     */
    public static <T> ConfigKey<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser
    ) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Config key must not be blank");
        }
        return new ConfigKey<>(key, type, defaultValue,
            java.util.Objects.requireNonNull(parser, "parser"));
    }

    /** Parses a raw value, or returns the default for null/blank input. */
    public T parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return parser.apply(raw.strip());
    }
}
