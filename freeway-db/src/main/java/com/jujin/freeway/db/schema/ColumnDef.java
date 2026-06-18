package com.jujin.freeway.db.schema;

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
        if (!nullable) {
            sb.append(" NOT NULL");
        }
        if (generated) {
            sb.append(' ').append(dialect.generatedClause());
        }
        return sb.toString();
    }

    /** Renders the column definition for ALTER TABLE ADD COLUMN. */
    String toAlterSql(Dialect dialect) {
        return "ADD COLUMN " + toSql(dialect);
    }

    /** Same as {@link #toAlterSql} but without the generated clause. */
    String toAlterSqlWithoutGenerated(Dialect dialect) {
        StringBuilder sb = new StringBuilder("ADD COLUMN ");
        sb.append(dialect.quoteName(name)).append(' ').append(sqlType);
        if (!nullable) {
            sb.append(" NOT NULL");
        }
        return sb.toString();
    }
}
