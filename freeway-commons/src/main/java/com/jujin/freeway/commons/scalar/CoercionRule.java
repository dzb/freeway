package com.jujin.freeway.commons.scalar;

import java.util.function.Function;

public record CoercionRule<S, T>(
    Class<S> sourceType,
    Class<T> targetType,
    Function<S, T> converter
) {
}
