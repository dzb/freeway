package com.jujin.freeway.db.schema;

import java.util.List;
import java.util.Objects;

/**
 * Index definition (internal use).
 */
record IndexDef(String name, List<String> columns, boolean unique) {

    IndexDef {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("index name must not be blank");
        }
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("columns must not be empty");
        }
    }

    /** Generates a CREATE INDEX statement. */
    String toSql(Dialect dialect, String tableName) {
        StringBuilder sb = new StringBuilder("CREATE ");
        if (unique) {
            sb.append("UNIQUE ");
        }
        sb.append("INDEX ");
        if (dialect.supportsIndexIfNotExists()) {
            sb.append("IF NOT EXISTS ");
        }
        sb.append(dialect.quoteName(name));
        sb.append(" ON ").append(dialect.quoteName(tableName));
        sb.append(" (");
        sb.append(String.join(", ", columns.stream().map(dialect::quoteName).toList()));
        sb.append(")");
        return sb.toString();
    }
}
