package com.jujin.freeway.db.schema;

/**
 * 单个列的定义（内部使用）。
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

    /** 转为 CREATE TABLE 中的列定义片段，例如 {@code "name VARCHAR(255) NOT NULL"}。 */
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

    /** 转为 ALTER TABLE ADD COLUMN 片段。 */
    String toAlterSql(Dialect dialect) {
        return "ADD COLUMN " + toSql(dialect);
    }
}
