package com.jujin.freeway.commons.coercion;

import java.util.function.Function;

/**
 * 类型转换规则记录类，用于定义从源类型到目标类型的转换逻辑。
 * <p>
 * 该记录封装了类型转换所需的三个要素：源类型、目标类型和转换器函数。
 * 主要用于注册自定义的类型转换规则，以扩展默认的类型转换能力。
 * </p>
 *
 * @param <S> 源类型
 * @param <T> 目标类型
 * @param sourceType 源类型Class类型
 * @param targetType 目标类型Class类型
 * @param mapping 执行实际转换的函数
 */
public record CoerceRule<S, T>(
    Class<S> sourceType,
    Class<T> targetType,
    Function<S, T> mapping
) {
}
