package com.jujin.freeway.commons.coercion;

import java.util.Map;
import java.util.Set;

/**
 * 类型转换器接口，提供将对象从一种类型转换为另一种类型的功能。
 * <p>
 * 这是类型转换系统的核心接口，定义了基本的转换方法以及查询转换能力的方法。
 * 实现类可以提供不同的转换策略，如基于规则的转换、基于配置的转换等。
 * </p>
 *
 * @author Freeway Team
 */
@FunctionalInterface
public interface Coercer {
    /**
     * 将给定值转换为目标类型的实例。
     *
     * @param <T>        目标类型的泛型参数
     * @param value      待转换的值，可以为 null
     * @param targetType 目标类型，不能为 null
     * @return 转换后的目标类型实例
     * @throws IllegalArgumentException 当 targetType 为 null 时抛出
     * @throws IllegalArgumentException 当转换失败时抛出
     */
    <T> T coerce(Object value, Class<T> targetType);

    /**
     * 判断此 Coercer 是否支持将给定源类型转换为目标类型。
     *
     * @param sourceType 源类型，不能为 null
     * @param targetType 目标类型，不能为 null
     * @return 如果支持该转换则返回 true，否则返回 false
     */
    default boolean supports(Class<?> sourceType, Class<?> targetType) {
        return false;
    }

    /**
     * 返回所有支持的转换映射：目标类型 → [源类型集合]。
     * 内置标量转换的源类型标记为 {@code Object.class}（表示任意源）。
     *
     * @return 不可修改的转换映射表，键为目标类型，值为支持的源类型集合
     */
    default Map<Class<?>, Set<Class<?>>> supported() {
        return Map.of();
    }
}
