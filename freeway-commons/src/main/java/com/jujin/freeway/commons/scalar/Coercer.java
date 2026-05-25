package com.jujin.freeway.commons.scalar;

@FunctionalInterface
public interface Coercer {
    <T> T coerce(Object value, Class<T> targetType);
}
