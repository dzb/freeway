package com.jujin.freeway.db.dialect;

import java.util.List;
import java.util.Objects;

/**
 * One complete upsert request: the insert shape plus the conflict action.
 * {@code table} and {@code columns} are SQL fragments supplied by the caller
 * (the {@code Sql} builder / {@code Orm}); {@code conflictColumns} and
 * {@code updateColumns} are raw names. The dialect decides whether to render
 * an INSERT suffix or a complete MERGE.
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
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Upsert requires at least one column");
        }
        if (conflictColumns.isEmpty()) {
            throw new IllegalArgumentException("Upsert requires at least one conflict column");
        }
        if (!doNothing && updateColumns.isEmpty()) {
            throw new IllegalArgumentException(
                "Upsert requires update columns unless doNothing is true");
        }
    }
}
