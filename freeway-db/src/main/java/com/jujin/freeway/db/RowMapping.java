package com.jujin.freeway.db;

import java.util.Objects;

public record RowMapping(Class<?> type, RowMapper<?> mapper) {
    public RowMapping {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
    }
}
