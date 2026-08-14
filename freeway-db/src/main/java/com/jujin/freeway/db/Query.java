package com.jujin.freeway.db;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface Query {

    Query param(String name, Object value);

    <T> List<T> list(Class<T> targetType);

    /**
     * Returns the first row of the result, or {@link Optional#empty()} when
     * the result has no rows.
     *
     * <p><b>Returns only the first row: a multi-row result is silently
     * truncated.</b> When the SQL may legitimately match more than one row,
     * use {@link #list(Class)} to detect the ambiguity — it returns every
     * row, so {@code size() > 1} reveals that {@code one()} would have
     * discarded data.
     *
     * @param targetType the row type
     * @param <T>        the row type
     * @return the first row, or empty when the result has no rows
     */
    <T> Optional<T> one(Class<T> targetType);

    <T> Stream<T> stream(Class<T> targetType);

    /** Executes INSERT / UPDATE / DELETE and returns the affected row count. Supports named parameters and collection expansion. */
    ExecuteResult execute();
}
