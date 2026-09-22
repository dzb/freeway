package com.jujin.freeway.ioc.symbol;

import com.jujin.freeway.commons.coercion.Coercer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    boolean required
) {

    /**
     * Creates an optional key whose value is parsed by the container
     * {@code Coercer} (no per-key parser needed — Duration, Boolean and
     * user-registered {@code CoerceRule} targets all work; lists have a
     * dedicated form in {@link #list}). Consume via
     * {@link #parse(String, Coercer)} — or, inside a container, the one-step
     * {@code resolve(spec)}.
     */
    public static <T> SymbolSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue
    ) {
        return of(key, type, defaultValue, null);
    }

    /** Creates an optional key with a default; absent/blank falls back. */
    public static <T> SymbolSpec<T> of(
        String key,
        Class<T> type,
        T defaultValue,
        Function<String, T> parser
    ) {
        return new SymbolSpec<>(
            normalizedKey(key, type, parser),
            type,
            defaultValue,
            parser,
            false
        );
    }

    /**
     * A comma-separated list-valued key — the framework's encoding for
     * multi-valued config, the same convention HTTP headers use for
     * multi-value fields ({@code Accept}, {@code Cache-Control}): split on
     * comma, trim each entry, drop empty entries. A key that is unset, set
     * to {@code ""}, or set to {@code " , ,"} must always mean the same
     * thing — an empty list — or a list silently changes meaning between
     * an absent key and an empty one. Entries must not contain commas
     * (the same limitation HTTP header lists carry).
     */
    public static SymbolSpec<List<String>> list(String key, List<String> defaultValue) {
        return new SymbolSpec<>(
            key,
            (Class<List<String>>) (Class<?>) List.class,
            defaultValue == null ? null : List.copyOf(defaultValue),
            SymbolSpec::splitList,
            false
        );
    }

    /**
     * This key with {@code fallback} as its default — the form a type uses when
     * it assembles itself from config: the default is the value the object
     * already holds, so the key table never restates a default that lives on the
     * type. Absent, blank and unparseable handling are unchanged; only the
     * fallback value differs.
     * <pre>{@code
     * cfg = cfg.withPort(symbols.resolve(PORT.orDefault(cfg.port())));
     * }</pre>
     */
    public SymbolSpec<T> orDefault(T fallback) {
        return new SymbolSpec<>(key, type, fallback, parser, required);
    }

    /** The list decoder — the single home of the comma-list encoding. */
    public static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    /**
     * The tri-state activation shape: an explicit value must be
     * {@code true} or {@code false} (case-insensitive) and wins; unset/blank
     * falls to the caller's presence-derived verdict. Unknown values fail
     * naming the key and the offending value. Knows no concrete keys — the
     * key and the presence signal come from the caller.
     */
    public static boolean activated(String key, String explicitValue, boolean presenceSignal) {
        String value = explicitValue == null || explicitValue.isBlank()
            ? null
            : explicitValue.trim().toLowerCase(Locale.ROOT);
        if (value == null) {
            return presenceSignal;
        }
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(
                key + " must be true or false: " + explicitValue);
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * The token-switch shape: the explicit value (case-insensitive) must be
     * one of {@code byToken}'s keys and maps to the target; unset/blank falls
     * to {@code whenUnset}. Unknown values fail naming the key, the valid
     * tokens and the offending value. Knows no concrete keys — the token
     * table and the default come from the caller.
     */
    public static <T> T mode(String key, String raw, Map<String, T> byToken, T whenUnset) {
        if (raw == null || raw.isBlank()) {
            return whenUnset;
        }
        String token = raw.trim().toLowerCase(Locale.ROOT);
        T mapped = byToken.get(token);
        if (mapped == null && !byToken.containsKey(token)) {
            throw new IllegalArgumentException(
                key + " must be one of " + byToken.keySet() + ": " + raw);
        }
        return mapped != null ? mapped : whenUnset;
    }

    /** Coercer-parsed required key: absent/blank input fails fast. */
    public static <T> SymbolSpec<T> required(
        String key,
        Class<T> type
    ) {
        return required(key, type, null);
    }

    /** Creates a required key: absent/blank input fails fast on parse. */
    public static <T> SymbolSpec<T> required(
        String key,
        Class<T> type,
        Function<String, T> parser
    ) {
        return new SymbolSpec<>(
            normalizedKey(key, type, parser),
            type,
            null,
            parser,
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
