package com.jujin.freeway.db;

import java.util.Objects;

/**
 * Keyed entry for {@link RowMapperRegistrations} contributions.
 */
public record RowMapperEntry(Class<?> type, RowMapper<?> mapper) {
    public RowMapperEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
    }
}
