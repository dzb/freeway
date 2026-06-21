package com.jujin.freeway.commons.coercion;

import java.util.function.Function;

/**
 * A record that defines a conversion rule from a source type to a target type.
 * <p>
 * Encapsulates three elements of a type conversion: the source type, the target
 * type, and the mapping function. Primarily used to register custom coercion
 * rules that extend the default coercion capabilities.
 * </p>
 *
 * @param <S>  the source type
 * @param <T>  the target type
 * @param sourceType  the source class
 * @param targetType  the target class
 * @param mapping     the conversion function
 */
public record CoerceRule<S, T>(
    Class<S> sourceType,
    Class<T> targetType,
    Function<S, T> mapping
) {}
