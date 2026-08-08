package com.jujin.freeway.commons.config;

import java.util.Objects;
import java.util.function.Function;

/**
 * A typed configuration key: key name, target type, default value, and a
 * parser from the raw string. Pairs with {@code AppConfig.get(ConfigProperty)}
 * for type-safe config access with centralized parsing and defaults — the
 * alternative to scattered {@code Integer.parseInt(...)} at each use site
 * with inconsistent error messages.
 *
 * <p>Lives in commons so every module (http, db, boot, …) can declare typed
 * config keys without depending on the boot layer. Parse errors and missing
 * required keys are reported with the key name in the message.
 *
 * <p>Example:
 * <pre>{@code
 * public static final ConfigProperty<Integer> HTTP_PORT =
 *     ConfigProperty.of("server.port", Integer.class, 8080, Integer::parseInt);
 * public static final ConfigProperty<String> DB_PASSWORD =
 *     ConfigProperty.required("db.password", String.class, Function.identity());
 *
 * int port = config.get(HTTP_PORT);       // typed, defaulted, parsed once
 * String pw = config.get(DB_PASSWORD);    // fails fast when absent
 * }</pre>
 *
 * @param <T> the value type
 */
public record ConfigProperty<T>(
    String key,
    Class<T> type,
    T defaultValue,
    Function<String, T> parser,
    String description,
    boolean required
) {

    /** Creates an optional key with a default; absent/blank falls back. */
    public static <T> ConfigProperty<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser
    ) {
        return of(key, type, defaultValue, parser, "");
    }

    /** Optional key with a human-readable description (docs/registry use). */
    public static <T> ConfigProperty<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser,
        String description
    ) {
        return new ConfigProperty<>(
            validate(key, type, parser),
            type,
            defaultValue,
            parser,
            Objects.requireNonNull(description, "description"),
            false
        );
    }

    /** Creates a required key: absent/blank input fails fast on parse. */
    public static <T> ConfigProperty<T> required(
        String key,
        Class<T> type,
        Function<String, T> parser
    ) {
        return required(key, type, parser, "");
    }

    /** Required key with a human-readable description. */
    public static <T> ConfigProperty<T> required(
        String key,
        Class<T> type,
        Function<String, T> parser,
        String description
    ) {
        return new ConfigProperty<>(
            validate(key, type, parser),
            type,
            null,
            parser,
            Objects.requireNonNull(description, "description"),
            true
        );
    }

    private static String validate(
        String key,
        Class<?> type,
        Function<String, ?> parser
    ) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Config key must not be blank");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parser, "parser");
        return key;
    }

    /**
     * Parses a raw value: the default (or {@code null}) for absent/blank
     * optional keys, the parsed value otherwise. Missing required keys and
     * malformed values throw {@link IllegalArgumentException} naming the key
     * — errors carry enough context to fix the config without a stack crawl.
     */
    public T parse(String raw) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(
                    "Missing required config key '" + key + "'");
            }
            return defaultValue;
        }
        String stripped = raw.strip();
        try {
            return parser.apply(stripped);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Invalid value for config key '" + key + "': '" + stripped + "'",
                ex
            );
        }
    }
}
