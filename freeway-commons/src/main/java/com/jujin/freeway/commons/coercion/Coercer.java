package com.jujin.freeway.commons.coercion;

@FunctionalInterface
public interface Coercer {
    <T> T coerce(Object value, Class<T> targetType);
}
