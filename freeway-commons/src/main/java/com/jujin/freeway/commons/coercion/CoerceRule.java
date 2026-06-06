package com.jujin.freeway.commons.coercion;

import java.util.function.Function;

public record CoerceRule<S, T>(
    Class<S> sourceType,
    Class<T> targetType,
    Function<S, T> converter
) {
}
