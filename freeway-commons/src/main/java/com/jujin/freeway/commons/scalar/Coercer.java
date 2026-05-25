package com.jujin.freeway2.commons.scalar;

@FunctionalInterface
public interface Coercer {
    <T> T coerce(Object value, Class<T> targetType);
}
