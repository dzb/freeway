package com.jujin.freeway.commons.config;
import com.jujin.freeway.commons.coercion.Coercer;

import java.util.Objects;
import java.util.function.Function;

/**
 * A typed configuration key: key name, target type, default value, and a
 * parser from the raw string. Pairs with {@code AppConfig.get(ConfigSpec)}
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
 * public static final ConfigSpec<Integer> HTTP_PORT =
 *     ConfigSpec.of("server.port", Integer.class, 8080, Integer::parseInt);
 * public static final ConfigSpec<String> DB_PASSWORD =
 *     ConfigSpec.required("db.password", String.class, Function.identity());
 *
 * int port = config.get(HTTP_PORT);       // typed, defaulted, parsed once
 * String pw = config.get(DB_PASSWORD);    // fails fast when absent
 * }</pre>
 *
 * @param <T> the value type
 */
public record ConfigSpec<T>(
    String key,
    Class<T> type,
    T defaultValue,
    Function<String, T> parser,
    String description,
    boolean required
) {

    /**
     * Creates an optional key whose value is parsed by the container
     * {@code Coercer} (no per-key parser needed — Duration, Boolean, List and
     * user-registered {@code CoerceRule} targets all work). Consume via
     * {@link #parse(String, Coercer)}.
     */
    public static <T> ConfigSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue
    ) {
        return of(key, type, defaultValue, null, "");
    }

    /** Coercer-parsed optional key with a human-readable description. */
    public static <T> ConfigSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        String description
    ) {
        return of(key, type, defaultValue, null, description);
    }

    /** Creates an optional key with a default; absent/blank falls back. */
    public static <T> ConfigSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser
    ) {
        return of(key, type, defaultValue, parser, "");
    }

    /** Optional key with a human-readable description (docs/registry use). */
    public static <T> ConfigSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser,
        String description
    ) {
        return new ConfigSpec<>(
            validate(key, type, parser),
            type,
            defaultValue,
            parser,
            Objects.requireNonNull(description, "description"),
            false
        );
    }

    /** Creates a required key: absent/blank input fails fast on parse. */
    public static <T> ConfigSpec<T> required(
        String key,
        Class<T> type,
        Function<String, T> parser
    ) {
        return required(key, type, parser, "");
    }

    /** Coercer-parsed required key: absent/blank input fails fast. */
    public static <T> ConfigSpec<T> required(
        String key,
        Class<T> type
    ) {
        return required(key, type, null, "");
    }

    /** Required key with a human-readable description. */
    public static <T> ConfigSpec<T> required(
        String key,
        Class<T> type,
        Function<String, T> parser,
        String description
    ) {
        return new ConfigSpec<>(
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
        // parser may be null — the coercer-parsed form (of/required without a
        // parser) resolves via parse(raw, Coercer) instead.
        return key;
    }

    /**
     * Parses a raw value: the default (or {@code null}) for absent/blank
     * optional keys, the parsed value otherwise. Missing required keys and
     * malformed values throw {@link IllegalArgumentException} naming the key
     * — errors carry enough context to fix the config without a stack crawl.
     */
    public T parse(String raw) {
        if (parser == null) {
            throw new IllegalStateException(
                "ConfigSpec '" + key + "' has no parser — resolve it via "
                    + "parse(raw, Coercer) with a container Coercer");
        }
        return parse(raw, null);
    }

    /**
     * Parses a raw value using the container {@code Coercer} when the spec
     * has no per-key parser (the {@code of(key, type, default)} form). A
     * spec with an explicit parser uses it regardless of the coercer.
     */
    public T parse(String raw, Coercer coercer) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(
                    "Missing required config key '" + key + "'");
            }
            return defaultValue;
        }
        String stripped = raw.strip();
        try {
            T value = parser != null
                ? parser.apply(stripped)
                : coercer.coerce(stripped, type);
            return value;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "Invalid value for config key '" + key + "': '" + stripped + "'",
                ex
            );
        }
    }
}
