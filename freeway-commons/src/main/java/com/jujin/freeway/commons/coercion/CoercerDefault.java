package com.jujin.freeway.commons.coercion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Map.entry;

public final class CoercerDefault implements Coercer {

    private final ConcurrentHashMap<CoercionKey, CoerceRule<?, ?>> rules =
        new ConcurrentHashMap<>();

    /** Rules indexed by target type for assignable-source lookups in {@link #supports}. */
    private final Map<Class<?>, List<CoerceRule<?, ?>>> rulesByTarget =
        new ConcurrentHashMap<>();

    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(
            boolean.class, Boolean.FALSE,
        char.class,     '\0',
        byte.class,         (byte) 0,
        short.class,        (short) 0,
        int.class,      0,
        long.class,     0L,
        float.class,    0f,
        double.class,   0d
    );

    public CoercerDefault register(CoerceRule<?, ?> rule) {
        if (rule == null) {
            throw new IllegalArgumentException("CoerceRule must not be null");
        }
        CoercionKey key = new CoercionKey(rule.sourceType(), rule.targetType());
        rules.put(key, rule);
        rulesByTarget
            .computeIfAbsent(rule.targetType(), k -> new CopyOnWriteArrayList<>())
            .add(rule);
        return this;
    }

    /**
     * Registers the rule only when no exact rule exists for the same
     * (source, target) pair — caller-registered rules keep priority.
     */
    public CoercerDefault registerIfAbsent(CoerceRule<?, ?> rule) {
        if (rule == null) {
            throw new IllegalArgumentException("CoerceRule must not be null");
        }
        CoercionKey key = new CoercionKey(rule.sourceType(), rule.targetType());
        if (rules.containsKey(key)) {
            return this;
        }
        return register(rule);
    }

    public void clearRules() {
        rules.clear();
        rulesByTarget.clear();
    }

    // --- static utilities ---

    public static Object defaultValue(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return null;
        }
        return PRIMITIVE_DEFAULTS.get(type);
    }

    public static Class<?> box(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type;
        }
        return BOXED_TYPES.get(type);
    }

    // --- supports / conversions ---

    @Override
    public boolean supports(Class<?> sourceType, Class<?> targetType) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");

        if (rules.containsKey(new CoercionKey(sourceType, targetType))) {
            return true;
        }

        List<CoerceRule<?, ?>> targetRules = rulesByTarget.get(targetType);
        if (targetRules != null) {
            for (CoerceRule<?, ?> rule : targetRules) {
                if (rule.sourceType().isAssignableFrom(sourceType)) {
                    return true;
                }
            }
        }

        return supportsBuiltin(sourceType, targetType);
    }

    @Override
    public Map<Class<?>, Set<Class<?>>> conversions() {
        Map<Class<?>, Set<Class<?>>> map = new LinkedHashMap<>();

        for (CoerceRule<?, ?> rule : rules.values()) {
            map.computeIfAbsent(rule.targetType(), k ->
                new LinkedHashSet<>()
            ).add(rule.sourceType());
        }

        for (Class<?> target : BUILTIN_COERCERS.keySet()) {
            map.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(
                Object.class
            );
        }

        return Collections.unmodifiableMap(map);
    }

    private static boolean supportsBuiltin(
        Class<?> sourceType,
        Class<?> targetType
    ) {
        if (sourceType == Void.class) return true;
        if (targetType.isAssignableFrom(sourceType)) return true;
        return (
            BUILTIN_COERCERS.containsKey(box(targetType)) || targetType.isEnum()
        );
    }

    // ==================================================================
    //  Built-in coercion dispatch
    // ==================================================================

    @FunctionalInterface
    private interface BuiltinCoercer {
        Object coerce(Object value);
    }

    private static final Map<Class<?>, BuiltinCoercer> BUILTIN_COERCERS =
        Map.ofEntries(
            // -- identity / string --
            entry(String.class, v -> String.valueOf(v)),

            // -- boolean --
            entry(Boolean.class, CoercerDefault::coerceToBoolean),
            entry(boolean.class, CoercerDefault::coerceToBoolean),

            // -- character --
            entry(Character.class, CoercerDefault::coerceToCharacter),
            entry(char.class, CoercerDefault::coerceToCharacter),

            // -- integral numbers --
            entry(Integer.class, v -> coerceNumber(v, Integer.class)),
            entry(int.class, v -> coerceNumber(v, Integer.class)),
            entry(Long.class, v -> coerceNumber(v, Long.class)),
            entry(long.class, v -> coerceNumber(v, Long.class)),
            entry(Short.class, v -> coerceNumber(v, Short.class)),
            entry(short.class, v -> coerceNumber(v, Short.class)),
            entry(Byte.class, v -> coerceNumber(v, Byte.class)),
            entry(byte.class, v -> coerceNumber(v, Byte.class)),

            // -- floating-point --
            entry(Double.class, v -> coerceNumber(v, Double.class)),
            entry(double.class, v -> coerceNumber(v, Double.class)),
            entry(Float.class, v -> coerceNumber(v, Float.class)),
            entry(float.class, v -> coerceNumber(v, Float.class)),

            // -- decimal --
            entry(BigDecimal.class, CoercerDefault::coerceToBigDecimal),
            entry(BigInteger.class, CoercerDefault::coerceToBigInteger),

            // -- temporal --
            entry(LocalDate.class, v -> LocalDate.parse(String.valueOf(v))),
            entry(LocalTime.class, v -> LocalTime.parse(String.valueOf(v))),
            entry(LocalDateTime.class, v ->
                LocalDateTime.parse(String.valueOf(v))
            ),
            entry(OffsetTime.class, v -> OffsetTime.parse(String.valueOf(v))),
            entry(OffsetDateTime.class, v ->
                OffsetDateTime.parse(String.valueOf(v))
            ),
            entry(ZonedDateTime.class, v ->
                ZonedDateTime.parse(String.valueOf(v))
            ),
            entry(Instant.class, v -> Instant.parse(String.valueOf(v))),

            // -- uuid --
            entry(UUID.class, v -> UUID.fromString(String.valueOf(v))),

            // -- uri / url / path / locale --
            entry(URI.class, v -> URI.create(String.valueOf(v))),
            entry(URL.class, CoercerDefault::coerceToURL),
            entry(Path.class, v -> Paths.get(String.valueOf(v))),
            entry(Locale.class, v -> Locale.forLanguageTag(String.valueOf(v))),

            // -- optional primitives --
            entry(OptionalInt.class, v -> OptionalInt.of(
                coerceNumber(v, Integer.class).intValue()
            )),
            entry(OptionalLong.class, v -> OptionalLong.of(
                coerceNumber(v, Long.class).longValue()
            )),
            entry(OptionalDouble.class, v -> OptionalDouble.of(
                coerceNumber(v, Double.class).doubleValue()
            )),
            entry(Optional.class, v -> Optional.of(v)),

            // -- duration --
            entry(Duration.class, CoercerDefault::coerceToDuration)
        );

    private static URL coerceToURL(Object value) {
        try { return URI.create(String.valueOf(value)).toURL(); }
        catch (Exception e) { throw new IllegalArgumentException("Cannot coerce to URL: " + value, e); }
    }

    @SuppressWarnings("unchecked")
    private static <T> T coerceInternal(Object value, Class<T> targetType) {
        Objects.requireNonNull(targetType, "targetType");
        if (value == null) {
            if (targetType == OptionalInt.class) return (T) OptionalInt.empty();
            if (targetType == OptionalLong.class) return (T) OptionalLong.empty();
            if (targetType == OptionalDouble.class) return (T) OptionalDouble.empty();
            if (targetType == Optional.class) return (T) Optional.empty();
            return (T) defaultValue(targetType);
        }
        if (targetType.isInstance(value)) {
            return targetType.cast(value);
        }
        BuiltinCoercer c = BUILTIN_COERCERS.get(box(targetType));
        if (c != null) {
            return (T) c.coerce(value);
        }
        if (targetType.isEnum()) {
            return (T) coerceToEnum(value, targetType);
        }
        throw new IllegalArgumentException(
            "Unsupported coercion to " + targetType.getName()
        );
    }

    // ==================================================================
    //  Per-type coercion methods (called from BUILTIN_COERCERS)
    // ==================================================================

    private static Boolean coerceToBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String text = String.valueOf(value).trim();
        if (
            "true".equalsIgnoreCase(text) ||
            "yes".equalsIgnoreCase(text) ||
            "on".equalsIgnoreCase(text) ||
            "1".equals(text)
        ) {
            return true;
        }
        if (
            "false".equalsIgnoreCase(text) ||
            "no".equalsIgnoreCase(text) ||
            "off".equalsIgnoreCase(text) ||
            "0".equals(text)
        ) {
            return false;
        }
        throw new IllegalArgumentException(
            "Unrecognized boolean value: " + value
        );
    }

    private static Character coerceToCharacter(Object value) {
        if (value instanceof Number n) {
            // Numeric sources follow the Java (char) cast semantics — code
            // point, not the decimal string's first character. 65 → 'A',
            // and out-of-range values fail loudly instead of truncating.
            if (
                (n instanceof Double d && (d.isNaN() || d.isInfinite())) ||
                (n instanceof Float f && (f.isNaN() || f.isInfinite()))
            ) {
                throw new IllegalArgumentException(
                    "Cannot coerce " + n + " to Character"
                );
            }
            // longValue() narrows BigInteger/BigDecimal to the low 64 bits,
            // so the range check must run on the exact value first.
            BigInteger bi = n instanceof BigDecimal bd
                ? bd.toBigInteger() // truncate the fraction, like (char)
                : n instanceof BigInteger i
                    ? i
                    : BigInteger.valueOf(n.longValue());
            if (
                bi.compareTo(BigInteger.ZERO) < 0 ||
                bi.compareTo(BigInteger.valueOf(Character.MAX_VALUE)) > 0
            ) {
                throw new IllegalArgumentException(
                    "Cannot coerce " + n + " to Character: out of range 0.."
                        + (int) Character.MAX_VALUE
                );
            }
            return (char) bi.intValue();
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? '\0' : text.charAt(0);
    }

    private static BigDecimal coerceToBigDecimal(Object value) {
        if (value instanceof BigDecimal d) return d;
        if (value instanceof BigInteger i) return new BigDecimal(i);
        if (value instanceof Number n) return new BigDecimal(String.valueOf(n));
        return new BigDecimal(String.valueOf(value).strip());
    }

    private static BigInteger coerceToBigInteger(Object value) {
        if (value instanceof BigInteger i) return i;
        if (value instanceof BigDecimal d) return d.toBigInteger();
        if (value instanceof Number n) {
            if (
                (n instanceof Double d && (d.isNaN() || d.isInfinite())) ||
                (n instanceof Float f && (f.isNaN() || f.isInfinite()))
            ) {
                throw new IllegalArgumentException(
                    "Cannot coerce " + n + " to BigInteger"
                );
            }
            // Route through the decimal representation: longValue() would
            // silently saturate out-of-range floats (1e30 → Long.MAX_VALUE).
            return new BigDecimal(String.valueOf(n)).toBigInteger();
        }
        String text = String.valueOf(value).strip();
        try {
            return new BigInteger(text);
        } catch (NumberFormatException e) {
            // Decimal or exponent notation ("1e30", "5.5") — the BigInteger
            // constructor rejects it; route through BigDecimal like the
            // Number path so the magnitudes match.
            return new BigDecimal(text).toBigInteger();
        }
    }

    private static Duration coerceToDuration(Object value) {
        String text = String.valueOf(value).trim();
        // ISO-8601 first (e.g. "PT1H30M" from Duration.toString()) — multi-unit
        // values would otherwise be misread by the single-suffix branches below
        // (and "PT1H30M" ends with 'm', hitting Long.parseLong("PT1H30")).
        try {
            return Duration.parse(text);
        } catch (DateTimeParseException ignored) {
            // fall through to the legacy single-suffix / raw-millis forms
        }
        if (text.endsWith("ms")) return Duration.ofMillis(
            Long.parseLong(text.substring(0, text.length() - 2).trim())
        );
        if (text.endsWith("s")) return Duration.ofSeconds(
            Long.parseLong(text.substring(0, text.length() - 1).trim())
        );
        if (text.endsWith("m")) return Duration.ofMinutes(
            Long.parseLong(text.substring(0, text.length() - 1).trim())
        );
        if (text.endsWith("h")) return Duration.ofHours(
            Long.parseLong(text.substring(0, text.length() - 1).trim())
        );
        return Duration.ofMillis(Long.parseLong(text));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Enum<T>> T coerceToEnum(
        Object value,
        Class<?> targetType
    ) {
        Class<T> enumType = (Class<T>) targetType.asSubclass(Enum.class);
        String name = String.valueOf(value);
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException e) {
            for (T constant : enumType.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(name)) {
                    return constant;
                }
            }
            throw e;
        }
    }

    // ==================================================================
    //  Number coercion
    // ==================================================================

    private static Number coerceNumber(Object value, Class<?> targetType) {
        if (value instanceof Number n) {
            return coerceNumberFromNumber(n, targetType);
        }
        return coerceNumberFromString(String.valueOf(value), targetType);
    }

    private static Number coerceNumberFromNumber(
        Number n,
        Class<?> targetType
    ) {
        // Guard against NaN/Infinity for integral targets
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
            throw new IllegalArgumentException("Cannot coerce " + n + " to " + targetType.getSimpleName());
        }
        if (n instanceof Float f && (f.isNaN() || f.isInfinite())) {
            throw new IllegalArgumentException("Cannot coerce " + n + " to " + targetType.getSimpleName());
        }
        // Guard against overflow for integral targets — every source type,
        // not just BigInteger/BigDecimal. Long/Double/Float sources silently
        // wrap via intValue()/shortValue()/byteValue(), corrupting data
        // (e.g. 3_000_000_000L → int must fail loudly, not become -1294967296).
        // Non-exact sources route through their decimal representation so
        // fractional values truncate exactly like the string path does.
        if (targetType != Double.class && targetType != Float.class) {
            if (n instanceof BigInteger bi) {
                checkRange(bi, targetType);
            } else if (n instanceof BigDecimal bd) {
                checkRange(bd, targetType);
            } else {
                checkRange(new BigDecimal(String.valueOf(n)), targetType);
            }
        }
        if (targetType == Integer.class) return n.intValue();
        if (targetType == Long.class) return n.longValue();
        if (targetType == Short.class) return n.shortValue();
        if (targetType == Byte.class) return n.byteValue();
        if (targetType == Double.class) {
            // BigDecimal and other non-float sources can overflow to Infinity
            // (e.g. 1e400 → Double) — reject instead of returning Infinity.
            double d = n.doubleValue();
            if (Double.isInfinite(d)) {
                throw new IllegalArgumentException(
                    "Cannot coerce " + n + " to Double: out of range"
                );
            }
            return d;
        }
        if (targetType == Float.class) {
            float f = n.floatValue();
            if (Float.isInfinite(f)) {
                throw new IllegalArgumentException(
                    "Cannot coerce " + n + " to Float: out of range"
                );
            }
            return f;
        }
        return null;
    }

    private static void checkRange(BigInteger bi, Class<?> targetType) {
        if (targetType == Integer.class || targetType == int.class) {
            if (bi.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0
                    || bi.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0)
                throw new IllegalArgumentException("Cannot coerce " + bi + " to Integer");
        } else if (targetType == Long.class || targetType == long.class) {
            if (bi.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                    || bi.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0)
                throw new IllegalArgumentException("Cannot coerce " + bi + " to Long");
        } else if (targetType == Short.class || targetType == short.class) {
            if (bi.compareTo(BigInteger.valueOf(Short.MAX_VALUE)) > 0
                    || bi.compareTo(BigInteger.valueOf(Short.MIN_VALUE)) < 0)
                throw new IllegalArgumentException("Cannot coerce " + bi + " to Short");
        } else if (targetType == Byte.class || targetType == byte.class) {
            if (bi.compareTo(BigInteger.valueOf(Byte.MAX_VALUE)) > 0
                    || bi.compareTo(BigInteger.valueOf(Byte.MIN_VALUE)) < 0)
                throw new IllegalArgumentException("Cannot coerce " + bi + " to Byte");
        }
    }

    private static void checkRange(BigDecimal bd, Class<?> targetType) {
        BigInteger bi = bd.toBigInteger();
        checkRange(bi, targetType);
    }

    private static Number coerceNumberFromString(
        String text,
        Class<?> targetType
    ) {
        // Trim like the Boolean/Duration paths do — " 12 " is a number.
        text = text.strip();
        if (
            targetType == Integer.class ||
            targetType == Long.class ||
            targetType == Short.class ||
            targetType == Byte.class
        ) {
            return parseIntegral(text, targetType);
        }
        if (targetType == Double.class) {
            double d = Double.parseDouble(text);
            if (Double.isNaN(d)) {
                // "NaN" (any case) parses to NaN — reject it exactly like
                // Infinity so a config value can never silently become
                // Double.NaN (which later explodes in stringify or coerces
                // to false).
                throw new IllegalArgumentException(
                    "Cannot coerce '" + text + "' to Double: not a finite number"
                );
            }
            if (Double.isInfinite(d)) {
                throw new IllegalArgumentException(
                    "Cannot coerce '" + text + "' to Double: out of range"
                );
            }
            return d;
        }
        if (targetType == Float.class) {
            float f = Float.parseFloat(text);
            if (Float.isNaN(f)) {
                throw new IllegalArgumentException(
                    "Cannot coerce '" + text + "' to Float: not a finite number"
                );
            }
            if (Float.isInfinite(f)) {
                throw new IllegalArgumentException(
                    "Cannot coerce '" + text + "' to Float: out of range"
                );
            }
            return f;
        }
        return null;
    }

    /**
     * Parses an integral target from a string. Plain parse first; on a
     * {@link NumberFormatException} (decimal string or out-of-range literal)
     * route through {@link BigDecimal} with a range check so oversized values
     * fail loudly instead of wrapping, and fractional values truncate
     * consistently across all four integral targets.
     */
    private static Number parseIntegral(String text, Class<?> targetType) {
        try {
            if (targetType == Integer.class) return Integer.parseInt(text);
            if (targetType == Long.class) return Long.parseLong(text);
            if (targetType == Short.class) return Short.parseShort(text);
            return Byte.parseByte(text);
        } catch (NumberFormatException e) {
            BigDecimal bd = new BigDecimal(text);
            checkRange(bd, targetType);
            if (targetType == Integer.class) return bd.intValue();
            if (targetType == Long.class) return bd.longValue();
            if (targetType == Short.class) return bd.shortValue();
            return bd.byteValue();
        }
    }

    // ==================================================================
    //  Constants
    // ==================================================================

    @Override
    @SuppressWarnings("unchecked")
    public <T> T coerce(Object input, Class<T> targetType) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType must not be null");
        }

        Class<?> sourceType = input == null ? Void.class : input.getClass();
        CoercionKey key = new CoercionKey(sourceType, targetType);

        CoerceRule<?, ?> rule = rules.get(key);
        // Fall back to rules whose source type is a supertype of the input
        // (e.g. a Number → String rule applies to Integer inputs), matching
        // what supports() already advertises. Null inputs keep their built-in
        // semantics and never trigger custom rules.
        if (rule == null && input != null) {
            rule = findAssignableRule(sourceType, targetType);
        }
        if (rule != null) {
            try {
                return (T) ((CoerceRule<Object, Object>) rule)
                    .mapping().apply(rule.sourceType().cast(input));
            } catch (Exception e) {
                throw new IllegalArgumentException(
                    String.format(
                        "Failed to coerce %s to %s using custom rule",
                        sourceType.getSimpleName(),
                        targetType.getSimpleName()
                    ),
                    e
                );
            }
        }

        try {
            return coerceInternal(input, targetType);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                String.format(
                    "Failed to coerce %s to %s",
                    sourceType.getSimpleName(),
                    targetType.getSimpleName()
                ),
                e
            );
        }
    }

    /**
     * Finds the most specific custom rule whose source type is assignable
     * from the given input type. Ties keep insertion order.
     */
    private CoerceRule<?, ?> findAssignableRule(
        Class<?> sourceType,
        Class<?> targetType
    ) {
        List<CoerceRule<?, ?>> targetRules = rulesByTarget.get(targetType);
        if (targetRules == null) {
            return null;
        }
        CoerceRule<?, ?> best = null;
        for (CoerceRule<?, ?> rule : targetRules) {
            if (!rule.sourceType().isAssignableFrom(sourceType)) {
                continue;
            }
            if (best == null
                    || best.sourceType().isAssignableFrom(rule.sourceType())) {
                // rule's source type is more specific than the current best
                best = rule;
            }
        }
        return best;
    }

    private static final Map<Class<?>, Class<?>> BOXED_TYPES = Map.of(
        boolean.class,     Boolean.class,
        byte.class,        Byte.class,
        short.class,       Short.class,
        int.class,         Integer.class,
        long.class,        Long.class,
        float.class,       Float.class,
        double.class,      Double.class,
        char.class,        Character.class
    );

    private record CoercionKey(Class<?> sourceType, Class<?> targetType) {
        private CoercionKey {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(targetType, "targetType");
        }
    }
}
