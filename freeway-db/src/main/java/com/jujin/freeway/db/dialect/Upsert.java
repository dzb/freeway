package com.jujin.freeway.db.dialect;

import java.util.List;
import java.util.Objects;

/**
 * One complete upsert request: the insert shape plus the conflict action.
 * Table and column strings are SQL fragments already owned by the caller
 * (the {@code Sql} builder / {@code Orm}); the dialect decides whether to
 * render them as an INSERT suffix or a complete MERGE.
 */
public record Upsert(
    String table,
    List<String> columns,
    String placeholders,
    List<String> conflictColumns,
    List<String> updateColumns,
    boolean doNothing
) {
    public Upsert {
        Objects.requireNonNull(table, "table");
        columns = columns == null ? List.of() : List.copyOf(columns);
        placeholders = Objects.requireNonNull(placeholders, "placeholders");
        conflictColumns = conflictColumns == null ? List.of() : List.copyOf(conflictColumns);
        updateColumns = updateColumns == null ? List.of() : List.copyOf(updateColumns);
    }
}
