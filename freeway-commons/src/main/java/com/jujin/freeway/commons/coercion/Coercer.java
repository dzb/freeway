package com.jujin.freeway.commons.coercion;

import java.util.Map;
import java.util.Set;

/**
 * Type coercion interface for converting values from one type to another.
 * <p>
 * This is the core interface of the type coercion system, defining conversion
 * methods and introspection capabilities. Implementations may provide different
 * strategies such as rule-based or configuration-driven conversion.
 * </p>
 */
@FunctionalInterface
public interface Coercer {
    /**
     * Converts the given value to an instance of the target type.
     *
     * @param <T>        the target type parameter
     * @param value      the value to convert, may be null
     * @param targetType the target type, must not be null
     * @return the converted instance of the target type
     * @throws IllegalArgumentException if targetType is null
     * @throws IllegalArgumentException if the conversion fails
     */
    <T> T coerce(Object value, Class<T> targetType);

    /**
     * Checks whether this coercer supports conversion from the given source
     * type to the given target type.
     *
     * @param sourceType the source type, must not be null
     * @param targetType the target type, must not be null
     * @return true if the coercion is supported, false otherwise
     */
    default boolean supports(Class<?> sourceType, Class<?> targetType) {
        return false;
    }

    /**
     * Returns a map of all supported conversions: target type → [source types].
     * Built-in scalar coercions list {@code Object.class} as the source type
     * (meaning any source is accepted).
     *
     * @return an unmodifiable conversion map keyed by target type
     */
    default Map<Class<?>, Set<Class<?>>> conversions() {
        return Map.of();
    }
}
