package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.dialect.Dialect;

/**
 * Single column definition (internal use).
 */
record ColumnDef(String name, String sqlType, boolean nullable, boolean primaryKey, boolean generated) {

    ColumnDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("column name must not be blank");
        }
        if (sqlType == null || sqlType.isBlank()) {
            throw new IllegalArgumentException("sqlType must not be blank");
        }
    }

    /** Renders the column definition for CREATE TABLE, e.g. {@code "name VARCHAR(255) NOT NULL"}. */
    String toSql(Dialect dialect) {
        StringBuilder sb = new StringBuilder();
        sb.append(dialect.quoteName(name)).append(' ').append(sqlType);
        if (generated && primaryKey && dialect.generatedPrimaryKeyInline()) {
            // SQLite declares the generated PK inline on the column:
            // "id" INTEGER PRIMARY KEY AUTOINCREMENT
            sb.append(" PRIMARY KEY ").append(dialect.generatedClause());
            return sb.toString();
        }
        if (!nullable) {
            sb.append(" NOT NULL");
        }
        if (generated) {
            sb.append(' ').append(dialect.generatedClause());
        }
        return sb.toString();
    }

    /**
     * Renders ALTER TABLE ADD COLUMN with optional NOT NULL and generated clause.
     * Used by dialects that restrict ALTER TABLE (e.g. SQLite strips both).
     */
    String toAlterSql(Dialect dialect, boolean includeNotNull, boolean includeGenerated) {
        StringBuilder sb = new StringBuilder("ADD COLUMN ");
        sb.append(dialect.quoteName(name)).append(' ').append(sqlType);
        if (includeNotNull && !nullable) {
            sb.append(" NOT NULL");
        }
        if (includeGenerated && generated) {
            sb.append(' ').append(dialect.generatedClause());
        }
        return sb.toString();
    }
}
