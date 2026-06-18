package com.jujin.freeway.commons.coercion;

import static java.util.Map.entry;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CoercerDefault implements Coercer {

    private final ConcurrentHashMap<CoercionKey, CoerceRule<?, ?>> rules =
        new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> T coerce(Object input, Class<T> targetType) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType must not be null");
        }

        Class<?> sourceType = input == null ? Void.class : input.getClass();
        CoercionKey key = new CoercionKey(sourceType, targetType);

        CoerceRule<Object, Object> rule = (CoerceRule<
            Object,
            Object
        >) rules.get(key);
        if (rule != null) {
            try {
                return (T) rule
                    .converter()
                    .apply(rule.sourceType().cast(input));
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

    public CoercerDefault register(CoerceRule<?, ?> rule) {
        if (rule == null) {
            throw new IllegalArgumentException("CoerceRule must not be null");
        }
        CoercionKey key = new CoercionKey(rule.sourceType(), rule.targetType());
        rules.put(key, rule);
        return this;
    }

    public void clearRules() {
        rules.clear();
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

    // --- supports / supported ---

    @Override
    public boolean supports(Class<?> sourceType, Class<?> targetType) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");

        if (rules.containsKey(new CoercionKey(sourceType, targetType))) {
            return true;
        }

        for (CoercionKey k : rules.keySet()) {
            if (
                k.targetType() == targetType &&
                k.sourceType().isAssignableFrom(sourceType)
            ) {
                return true;
            }
        }

        return supportsBuiltin(sourceType, targetType);
    }

    @Override
    public Map<Class<?>, Set<Class<?>>> supported() {
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

            // -- duration --
            entry(Duration.class, CoercerDefault::coerceToDuration)
        );

    @SuppressWarnings("unchecked")
    private static <T> T coerceInternal(Object value, Class<T> targetType) {
        Objects.requireNonNull(targetType, "targetType");
        if (value == null) {
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
        String text = String.valueOf(value);
        return text.isEmpty() ? '\0' : text.charAt(0);
    }

    private static BigDecimal coerceToBigDecimal(Object value) {
        if (value instanceof BigDecimal d) return d;
        if (value instanceof BigInteger i) return new BigDecimal(i);
        if (value instanceof Number n) return new BigDecimal(String.valueOf(n));
        return new BigDecimal(String.valueOf(value));
    }

    private static BigInteger coerceToBigInteger(Object value) {
        if (value instanceof BigInteger i) return i;
        if (value instanceof BigDecimal d) return d.toBigInteger();
        if (value instanceof Number n) return BigInteger.valueOf(n.longValue());
        return new BigInteger(String.valueOf(value));
    }

    private static Duration coerceToDuration(Object value) {
        String text = String.valueOf(value).trim();
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
        if (targetType == Integer.class) return n.intValue();
        if (targetType == Long.class) return n.longValue();
        if (targetType == Short.class) return n.shortValue();
        if (targetType == Byte.class) return n.byteValue();
        if (targetType == Double.class) return n.doubleValue();
        if (targetType == Float.class) return n.floatValue();
        return null;
    }

    private static Number coerceNumberFromString(
        String text,
        Class<?> targetType
    ) {
        if (targetType == Integer.class) return parseInteger(text);
        if (targetType == Long.class) return parseLong(text);
        if (targetType == Short.class) return parseShort(text);
        if (targetType == Byte.class) return parseByte(text);
        if (targetType == Double.class) return Double.parseDouble(text);
        if (targetType == Float.class) return Float.parseFloat(text);
        return null;
    }

    private static Number parseInteger(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return new BigDecimal(text).intValue();
        }
    }

    private static Number parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return new BigDecimal(text).longValue();
        }
    }

    private static Number parseShort(String text) {
        try {
            return Short.parseShort(text);
        } catch (NumberFormatException e) {
            return new BigDecimal(text).shortValue();
        }
    }

    private static Number parseByte(String text) {
        try {
            return Byte.parseByte(text);
        } catch (NumberFormatException e) {
            return new BigDecimal(text).byteValue();
        }
    }

    // ==================================================================
    //  Constants
    // ==================================================================

    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(
        boolean.class,
        Boolean.FALSE,
        char.class,
        '\0',
        byte.class,
        (byte) 0,
        short.class,
        (short) 0,
        int.class,
        0,
        long.class,
        0L,
        float.class,
        0f,
        double.class,
        0d
    );

    private static final Map<Class<?>, Class<?>> BOXED_TYPES = Map.of(
        boolean.class,
        Boolean.class,
        byte.class,
        Byte.class,
        short.class,
        Short.class,
        int.class,
        Integer.class,
        long.class,
        Long.class,
        float.class,
        Float.class,
        double.class,
        Double.class,
        char.class,
        Character.class
    );

    private record CoercionKey(Class<?> sourceType, Class<?> targetType) {
        private CoercionKey {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(targetType, "targetType");
        }
    }
}
