package com.jujin.freeway.commons.coercion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认的类型转换器实现，提供丰富的内置类型转换功能。
 * <p>
 * 该类实现了 {@link Coercer} 接口，支持以下类型的转换：
 * <ul>
 *   <li>基本数据类型及其包装类之间的转换</li>
 *   <li>字符串到各种数值类型的转换</li>
 *   <li>数值类型之间的转换</li>
 *   <li>布尔值、字符类型的转换</li>
 *   <li>BigDecimal 和 BigInteger 的转换</li>
 *   <li>枚举类型的转换</li>
 *   <li>支持注册自定义转换规则</li>
 * </ul>
 * </p>
 * <p>
 * 转换逻辑遵循优先级顺序：首先尝试使用自定义注册的规则，然后使用内置的转换逻辑。
 * 该类是线程安全的，可以在多线程环境中安全使用。
 * </p>
 *
 * @author Freeway Team
 */
public final class CoercerDefault implements Coercer {

    private final ConcurrentHashMap<CoercionKey, CoerceRule<?, ?>> rules =
        new ConcurrentHashMap<>();

    /**
     * 将输入对象转换为目标类型的实例。
     * <p>
     * 转换逻辑遵循以下优先级：
     * <ol>
     *   <li>首先查找是否存在针对源类型和目标类型的自定义转换规则</li>
     *   <li>如果存在自定义规则，则应用该规则进行转换</li>
     *   <li>如果不存在自定义规则，则使用缓存的默认转换逻辑</li>
     * </ol>
     *
     * @param <T>        目标类型的泛型参数
     * @param input      待转换的输入对象，可以为 null
     * @param targetType 目标类型，不能为 null
     * @return 转换后的目标类型实例，如果转换失败可能抛出异常
     * @throws IllegalArgumentException 当 targetType 为 null 时抛出
     * @throws IllegalArgumentException 当转换过程中发生错误时抛出，包含原始异常信息
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T coerce(Object input, Class<T> targetType) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType must not be null");
        }

        Class<?> sourceType = input == null ? Void.class : input.getClass();
        CoercionKey key = new CoercionKey(sourceType, targetType);

        // 优先使用自定义规则
        CoerceRule<Object, Object> rule = (CoerceRule<
            Object,
            Object
        >) rules.get(key);
        if (rule != null) {
            try {
                //noinspection unchecked
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

        // 使用内置转换逻辑
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
     * 注册一个自定义的类型转换规则。
     * <p>
     * 该方法会将规则存储到自定义规则映射中，并清除对应的缓存转换逻辑，
     * 确保新注册的规则在后续转换时优先生效。
     * </p>
     * <p>
     * 注意：在并发环境下，该方法的调用方需要自行保证线程安全。
     * </p>
     *
     * @param rule 要注册的转换规则，不能为 null
     * @return 当前 CoercerDefault 实例，支持链式调用
     * @throws NullPointerException 当 rule 为 null 时抛出
     */
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

    // --- static utilities (ex-ScalarCoercions) ---

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

    // --- canCoerce / conversions ---

    private static final Set<Class<?>> BUILTIN_SCALAR_TARGETS = Set.of(
        String.class,
        Boolean.class,
        boolean.class,
        Character.class,
        char.class,
        Integer.class,
        int.class,
        Long.class,
        long.class,
        Short.class,
        short.class,
        Byte.class,
        byte.class,
        Double.class,
        double.class,
        Float.class,
        float.class,
        BigDecimal.class,
        BigInteger.class,
        LocalDate.class,
        LocalTime.class,
        LocalDateTime.class,
        OffsetTime.class,
        OffsetDateTime.class,
        ZonedDateTime.class,
        Instant.class,
        UUID.class,
        Duration.class
    );

    @Override
    public boolean canCoerce(Class<?> sourceType, Class<?> targetType) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");

        // Step 1: 精确匹配自定义规则
        if (rules.containsKey(new CoercionKey(sourceType, targetType))) {
            return true;
        }

        // Step 2: 兼容匹配自定义规则（子类/实现类）
        for (CoercionKey k : rules.keySet()) {
            if (
                k.targetType() == targetType &&
                k.sourceType().isAssignableFrom(sourceType)
            ) {
                return true;
            }
        }

        // Step 3: 内置转换能力
        return canCoerceBuiltin(sourceType, targetType);
    }

    @Override
    public Map<Class<?>, Set<Class<?>>> conversions() {
        Map<Class<?>, Set<Class<?>>> map = new LinkedHashMap<>();

        // 自定义规则
        for (CoerceRule<?, ?> rule : rules.values()) {
            map.computeIfAbsent(rule.targetType(), k ->
                new LinkedHashSet<>()
            ).add(rule.sourceType());
        }

        // 内置标量 — Object.class 表示任意源类型
        for (Class<?> target : BUILTIN_SCALAR_TARGETS) {
            map.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(
                Object.class
            );
        }

        return Collections.unmodifiableMap(map);
    }

    private static boolean canCoerceBuiltin(
        Class<?> sourceType,
        Class<?> targetType
    ) {
        // null → any (默认值)
        if (sourceType == Void.class) return true;
        // identity / 继承
        if (targetType.isAssignableFrom(sourceType)) return true;
        // 标量转换
        Class<?> boxedTarget = box(targetType);
        if (boxedTarget == null) return false;
        if (BUILTIN_SCALAR_TARGETS.contains(boxedTarget)) return true;
        return boxedTarget.isEnum();
    }

    // --- internal: core scalar coercion logic (ex-ScalarCoercions.coerce) ---

    @SuppressWarnings("unchecked")
    private static <T> T coerceInternal(Object value, Class<T> targetType) {
        Objects.requireNonNull(targetType, "targetType");
        if (value == null) {
            return (T) defaultValue(targetType);
        }
        if (targetType.isInstance(value)) {
            return targetType.cast(value);
        }
        Class<?> boxedTarget = box(targetType);
        if (boxedTarget == String.class) {
            return (T) String.valueOf(value);
        }
        if (boxedTarget == Boolean.class) {
            return (T) Boolean.valueOf(parseBool(value));
        }
        if (boxedTarget == Character.class) {
            String text = String.valueOf(value);
            return (T) Character.valueOf(
                text.isEmpty() ? '\0' : text.charAt(0)
            );
        }
        Number result = coerceNumber(value, boxedTarget);
        if (result != null) {
            return (T) result;
        }
        if (boxedTarget == BigDecimal.class) {
            if (value instanceof BigDecimal decimal) {
                return (T) decimal;
            }
            if (value instanceof BigInteger integer) {
                return (T) new BigDecimal(integer);
            }
            if (value instanceof Number number) {
                return (T) new BigDecimal(String.valueOf(number));
            }
            return (T) new BigDecimal(String.valueOf(value));
        }
        if (boxedTarget == BigInteger.class) {
            if (value instanceof BigInteger integer) {
                return (T) integer;
            }
            if (value instanceof BigDecimal decimal) {
                return (T) decimal.toBigInteger();
            }
            if (value instanceof Number number) {
                return (T) BigInteger.valueOf(number.longValue());
            }
            return (T) new BigInteger(String.valueOf(value));
        }
        if (boxedTarget == LocalDate.class) {
            return (T) parseTemporalValue(
                value,
                LocalDate::parse,
                "LocalDate"
            );
        }
        if (boxedTarget == LocalTime.class) {
            return (T) parseTemporalValue(
                value,
                LocalTime::parse,
                "LocalTime"
            );
        }
        if (boxedTarget == LocalDateTime.class) {
            return (T) parseTemporalValue(
                value,
                LocalDateTime::parse,
                "LocalDateTime"
            );
        }
        if (boxedTarget == OffsetTime.class) {
            return (T) parseTemporalValue(
                value,
                OffsetTime::parse,
                "OffsetTime"
            );
        }
        if (boxedTarget == OffsetDateTime.class) {
            return (T) parseTemporalValue(
                value,
                OffsetDateTime::parse,
                "OffsetDateTime"
            );
        }
        if (boxedTarget == ZonedDateTime.class) {
            return (T) parseTemporalValue(
                value,
                ZonedDateTime::parse,
                "ZonedDateTime"
            );
        }
        if (boxedTarget == Instant.class) {
            return (T) parseTemporalValue(value, Instant::parse, "Instant");
        }
        if (boxedTarget == UUID.class) {
            return (T) parseTemporalValue(
                value,
                UUID::fromString,
                "UUID"
            );
        }
        if (boxedTarget == Duration.class) {
            return (T) parseDuration(String.valueOf(value));
        }
        if (boxedTarget.isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumType = (Class<
                ? extends Enum
            >) boxedTarget.asSubclass(Enum.class);
            String name = String.valueOf(value);
            try {
                return (T) Enum.valueOf(enumType, name);
            } catch (IllegalArgumentException e) {
                // 大小写不敏感回退
                for (Enum<?> constant : enumType.getEnumConstants()) {
                    if (constant.name().equalsIgnoreCase(name)) {
                        return (T) constant;
                    }
                }
                throw e;
            }
        }
        throw new IllegalArgumentException(
            "Unsupported coercion to " + targetType.getName()
        );
    }

    @FunctionalInterface
    private interface TemporalParser<T> {
        T parse(String text);
    }

    private static <T> T parseTemporalValue(
        Object value,
        TemporalParser<T> parser,
        String typeName
    ) {
        try {
            return parser.parse(String.valueOf(value));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                "Invalid " + typeName + " value: " + value,
                e
            );
        }
    }

    private static Duration parseDuration(String text) {
        String value = text.trim();
        if (value.endsWith("ms")) return Duration.ofMillis(
            Long.parseLong(value.substring(0, value.length() - 2).trim())
        );
        if (value.endsWith("s")) return Duration.ofSeconds(
            Long.parseLong(value.substring(0, value.length() - 1).trim())
        );
        if (value.endsWith("m")) return Duration.ofMinutes(
            Long.parseLong(value.substring(0, value.length() - 1).trim())
        );
        if (value.endsWith("h")) return Duration.ofHours(
            Long.parseLong(value.substring(0, value.length() - 1).trim())
        );
        return Duration.ofMillis(Long.parseLong(value));
    }

    private static boolean parseBool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)
            || "yes".equalsIgnoreCase(text)
            || "on".equalsIgnoreCase(text)
            || "1".equals(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)
            || "no".equalsIgnoreCase(text)
            || "off".equalsIgnoreCase(text)
            || "0".equals(text)) {
            return false;
        }
        throw new IllegalArgumentException("Unrecognized boolean value: " + value);
    }

    // --- internal: number coercion helpers (ex-ScalarCoercions) ---

    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(
        boolean.class,
        Boolean.FALSE,
        char.class,
        Character.valueOf('\0'),
        byte.class,
        Byte.valueOf((byte) 0),
        short.class,
        Short.valueOf((short) 0),
        int.class,
        Integer.valueOf(0),
        long.class,
        Long.valueOf(0L),
        float.class,
        Float.valueOf(0f),
        double.class,
        Double.valueOf(0d)
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

    @FunctionalInterface
    private interface NumberParser {
        Number parse(String text);
    }

    private static final Map<Class<?>, NumberParser> INT_PARSERS = Map.of(
        Integer.class,
        text -> Integer.valueOf(Integer.parseInt(text)),
        Long.class,
        text -> Long.valueOf(Long.parseLong(text)),
        Short.class,
        text -> Short.valueOf(Short.parseShort(text)),
        Byte.class,
        text -> Byte.valueOf(Byte.parseByte(text))
    );

    private static final Map<Class<?>, NumberParser> FP_PARSERS = Map.of(
        Double.class,
        text -> Double.valueOf(text),
        Float.class,
        text -> Float.valueOf(text)
    );

    @SuppressWarnings("SameParameterValue")
    private static Number coerceNumber(Object value, Class<?> targetType) {
        NumberParser fp = FP_PARSERS.get(targetType);
        if (fp != null) {
            if (value instanceof Number number) {
                return fp.parse(number.toString());
            }
            return fp.parse(String.valueOf(value));
        }
        NumberParser ip = INT_PARSERS.get(targetType);
        if (ip != null) {
            if (value instanceof Number number) {
                return ip.parse(String.valueOf(number.longValue()));
            }
            String text = String.valueOf(value);
            try {
                return ip.parse(text);
            } catch (NumberFormatException ignored) {
                return ip.parse(new BigDecimal(text).toBigInteger().toString());
            }
        }
        return null;
    }

    private record CoercionKey(Class<?> sourceType, Class<?> targetType) {
        private CoercionKey {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(targetType, "targetType");
        }
    }
}
