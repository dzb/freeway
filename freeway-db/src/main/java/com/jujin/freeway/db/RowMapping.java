package com.jujin.freeway2.db;

import java.util.Objects;

public record RowMapping<T>(
    Class<T> type,
    RowMapper<? extends T> mapper
) {
    public RowMapping {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
    }

    public static <T> RowMapping<T> of(
        Class<T> type,
        RowMapper<? extends T> mapper
    ) {
        return new RowMapping<>(type, mapper);
    }
}
