package com.jujin.freeway.db;

import java.util.Objects;

/**
 * Binds a {@link RowMapper} to a target type, for use in IoC contribution.
 *
 * @param type   the target type to map rows into
 * @param mapper the row mapper function
 */
public record RowMapping(Class<?> type, RowMapper<?> mapper) {
    public RowMapping {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mapper, "mapper");
    }
}
