package com.jujin.freeway2.commons.scalar;

import java.util.function.Function;

public record CoercionRule<S, T>(
    Class<S> sourceType,
    Class<T> targetType,
    Function<S, T> converter
) {
}
