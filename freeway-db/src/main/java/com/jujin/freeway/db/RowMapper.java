package com.jujin.freeway.db;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a JDBC {@link ResultSet} row to a domain object.
 * <p>Register custom mappers via {@code binder.contribute(RowMapping.class)} in IoC mode.
 *
 * @param <T> the target domain type
 */
@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet rs, int rowNum) throws SQLException;
}
