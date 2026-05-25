package com.jujin.freeway.commons.scalar;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultCoercer implements Coercer {
    private final ConcurrentHashMap<CoercionKey, Coercer> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CoercionKey, Coercer> custom = new ConcurrentHashMap<>();

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
     * @throws IllegalStateException    当转换过程中发生错误时抛出，包含原始异常信息
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
        Coercer customCoercer = custom.get(key);
        if (customCoercer != null) {
            try {
                return (T) customCoercer.coerce(input, targetType);
            } catch (Exception e) {
                throw new IllegalStateException(
                    String.format("Failed to coerce %s to %s using custom rule", sourceType.getSimpleName(), targetType.getSimpleName()),
                    e
                );
            }
        }

        // 使用缓存的转换逻辑
        try {
            return (T) cache.computeIfAbsent(key, ignored -> createCoercion(sourceType, targetType))
                .coerce(input, targetType);
        } catch (Exception e) {
            throw new IllegalStateException(
                String.format("Failed to coerce %s to %s", sourceType.getSimpleName(), targetType.getSimpleName()),
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
     * @return 当前 DefaultCoercer 实例，支持链式调用
     * @throws NullPointerException 当 rule 为 null 时抛出
     */
    public DefaultCoercer register(CoercionRule<?, ?> rule) {
        if (rule == null) {
            throw new IllegalArgumentException("CoercionRule must not be null");
        }
        CoercionKey key = new CoercionKey(rule.sourceType(), rule.targetType());
        custom.put(key, wrap(rule));
        cache.remove(key);
        return this;
    }



    public void clearCache() {
        cache.clear();
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

    // --- internal: cached coercion factory ---

    private Coercer createCoercion(Class<?> sourceType, Class<?> targetType) {
        // Primitive targets must go through coerceInternal because
        // Class.cast() doesn't handle auto-unboxing
        if (targetType.isPrimitive()) {
            return DefaultCoercer::coerceInternal;
        }

        Class<?> boxedTarget = box(targetType);
        Class<?> boxedSource = sourceType == null ? null : box(sourceType);

        if (boxedSource != null && boxedTarget.isAssignableFrom(boxedSource)) {
            return DefaultCoercer::assignableCast;
        }

        return DefaultCoercer::coerceInternal;
    }

    private static <T> T assignableCast(Object input, Class<T> targetType) {
        return targetType.cast(input);
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
            if (value instanceof Boolean b) {
                return (T) b;
            }
            if (value instanceof Number n) {
                return (T) Boolean.valueOf(n.intValue() != 0);
            }
            String text = String.valueOf(value);
            if ("true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text)
                || "on".equalsIgnoreCase(text) || "1".equals(text)) {
                return (T) Boolean.TRUE;
            }
            return (T) Boolean.FALSE;
        }
        if (boxedTarget == Character.class) {
            String text = String.valueOf(value);
            return (T) Character.valueOf(text.isEmpty() ? '\0' : text.charAt(0));
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
        if (boxedTarget.isEnum()) {
            @SuppressWarnings("unchecked")
            Class<? extends Enum> enumType = (Class<? extends Enum>) boxedTarget.asSubclass(Enum.class);
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
        throw new IllegalArgumentException("Unsupported coercion to " + targetType.getName());
    }

    // --- internal: number coercion helpers (ex-ScalarCoercions) ---

    private static final Map<Class<?>, Object> PRIMITIVE_DEFAULTS = Map.of(
        boolean.class, Boolean.FALSE,
        char.class, Character.valueOf('\0'),
        byte.class, Byte.valueOf((byte) 0),
        short.class, Short.valueOf((short) 0),
        int.class, Integer.valueOf(0),
        long.class, Long.valueOf(0L),
        float.class, Float.valueOf(0f),
        double.class, Double.valueOf(0d)
    );

    private static final Map<Class<?>, Class<?>> BOXED_TYPES = Map.of(
        boolean.class, Boolean.class,
        byte.class, Byte.class,
        short.class, Short.class,
        int.class, Integer.class,
        long.class, Long.class,
        float.class, Float.class,
        double.class, Double.class,
        char.class, Character.class
    );

    @FunctionalInterface
    private interface NumberParser {
        Number parse(String text);
    }

    private static final Map<Class<?>, NumberParser> INT_PARSERS = Map.of(
        Integer.class, text -> Integer.valueOf(Integer.parseInt(text)),
        Long.class, text -> Long.valueOf(Long.parseLong(text)),
        Short.class, text -> Short.valueOf(Short.parseShort(text)),
        Byte.class, text -> Byte.valueOf(Byte.parseByte(text))
    );

    private static final Map<Class<?>, NumberParser> FP_PARSERS = Map.of(
        Double.class, text -> Double.valueOf(text),
        Float.class, text -> Float.valueOf(text)
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

    // --- internal helpers ---

    @SuppressWarnings("unchecked")
    private static <S, T> Coercer wrap(CoercionRule<S, T> rule) {
        return new Coercer() {
            @Override
            public <T> T coerce(Object value, Class<T> targetType) {
                return (T) rule.converter().apply(rule.sourceType().cast(value));
            }
        };
    }

    private record CoercionKey(Class<?> sourceType, Class<?> targetType) {
        private CoercionKey {
            Objects.requireNonNull(sourceType, "sourceType");
            Objects.requireNonNull(targetType, "targetType");
        }
    }
}
