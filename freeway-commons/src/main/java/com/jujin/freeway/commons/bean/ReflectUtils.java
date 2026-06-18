package com.jujin.freeway.commons.bean;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Minimal reflection utilities used by the bean and coercion subsystems.
 */
public final class ReflectUtils {

    private ReflectUtils() {}

    /**
     * Extracts the raw {@link Class} from a {@link Type}.
     * Handles {@link Class}, {@link ParameterizedType}, and
     * {@link GenericArrayType}.
     *
     * @throws IllegalArgumentException if the type is not supported
     */
    public static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType pt
                && pt.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        if (type instanceof GenericArrayType arrayType) {
            return Array.newInstance(
                    rawClass(arrayType.getGenericComponentType()), 0)
                    .getClass();
        }
        throw new IllegalArgumentException(
                "Unsupported type: " + type.getTypeName());
    }
}
