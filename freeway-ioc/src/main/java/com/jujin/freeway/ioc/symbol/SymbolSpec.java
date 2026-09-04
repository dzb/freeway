package com.jujin.freeway.ioc.symbol;

import com.jujin.freeway.commons.coercion.Coercer;

import java.util.Objects;
import java.util.function.Function;

/**
 * A typed configuration key: key name, target type, default value, and a
 * parser from the raw string — the post-processing step over a resolved
 * value. The symbol chain ({@code SymbolSource}) answers every lookup with
 * a raw string; a spec turns that raw string into the typed form with
 * centralized parsing and defaults — the alternative to scattered
 * {@code Integer.parseInt(...)} at each use site with inconsistent error
 * messages.
 *
 * <p>Lives in the {@code ioc.symbol} package alongside {@link SymbolSource}
 * so every module (http, db, boot, …) can declare typed config keys without
 * depending on the boot layer. Parse errors and missing required keys are
 * reported with the key name in the message.
 *
 * <p>Example:
 * <pre>{@code
 * public static final SymbolSpec<Integer> HTTP_PORT =
 *     SymbolSpec.of("server.port", Integer.class, 8080, Integer::parseInt);
 * public static final SymbolSpec<String> DB_PASSWORD =
 *     SymbolSpec.required("db.password", String.class, Function.identity());
 *
 * // resolve raw, then post-process — key and default declared once
 * int port = symbols.resolve(HTTP_PORT);
 * String pw = symbols.resolve(DB_PASSWORD);
 * }</pre>
 *
 * @param <T> the value type
 */
public record SymbolSpec<T>(
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
    public static <T> SymbolSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue
    ) {
        return of(key, type, defaultValue, null, "");
    }

    /** Coercer-parsed optional key with a human-readable description. */
    public static <T> SymbolSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        String description
    ) {
        return of(key, type, defaultValue, null, description);
    }

    /** Creates an optional key with a default; absent/blank falls back. */
    public static <T> SymbolSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser
    ) {
        return of(key, type, defaultValue, parser, "");
    }

    /** Optional key with a human-readable description (docs/registry use). */
    public static <T> SymbolSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser,
        String description
    ) {
        return new SymbolSpec<>(
            normalizedKey(key, type, parser),
            type,
            defaultValue,
            parser,
            Objects.requireNonNull(description, "description"),
            false
        );
    }

    /** Creates a required key: absent/blank input fails fast on parse. */
    public static <T> SymbolSpec<T> required(
        String key,
        Class<T> type,
        Function<String, T> parser
    ) {
        return required(key, type, parser, "");
    }

    /** Coercer-parsed required key: absent/blank input fails fast. */
    public static <T> SymbolSpec<T> required(
        String key,
        Class<T> type
    ) {
        return required(key, type, null, "");
    }

    /** Required key with a human-readable description. */
    public static <T> SymbolSpec<T> required(
        String key,
        Class<T> type,
        Function<String, T> parser,
        String description
    ) {
        return new SymbolSpec<>(
            normalizedKey(key, type, parser),
            type,
            null,
            parser,
            Objects.requireNonNull(description, "description"),
            true
        );
    }

    private static String normalizedKey(
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
                "SymbolSpec '" + key + "' has no parser — resolve it via "
                    + "parse(raw, Coercer) with a container Coercer");
        }
        return parse(raw, null);
    }

    /**
     * Parses a raw value using the container {@code Coercer} when the spec
     * has no per-key parser (the {@code of(key, type, default)} form). A
     * spec with an explicit parser uses it regardless of the coercer.
     *
     * @throws IllegalStateException when the spec has no parser and no
     *         {@code coercer} was supplied — the error names the key
     */
    public T parse(String raw, Coercer coercer) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(
                    "Missing required config key '" + key + "'");
            }
            return defaultValue;
        }
        if (parser == null && coercer == null) {
            throw new IllegalStateException(
                "SymbolSpec '" + key + "' has no parser and no Coercer was"
                    + " supplied — resolve it via parse(raw, Coercer)");
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
