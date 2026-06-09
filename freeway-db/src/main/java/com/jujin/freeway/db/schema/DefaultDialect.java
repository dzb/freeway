package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 默认 SQL 方言（PostgreSQL / H2 PostgreSQL mode）。
 */
final class DefaultDialect implements Dialect {

    /** 常见 SQL 保留字，始终需要引用。 */
    private static final Set<String> RESERVED = Set.of(
        "user", "order", "group", "table", "select", "from", "where",
        "insert", "update", "delete", "create", "alter", "drop", "index",
        "primary", "key", "foreign", "references", "check", "constraint",
        "grant", "revoke", "role", "schema", "catalog", "database",
        "column", "view", "trigger", "function", "procedure", "sequence",
        "case", "when", "then", "else", "end", "as", "on", "off",
        "like", "in", "is", "not", "null", "and", "or", "between",
        "join", "inner", "left", "right", "outer", "cross", "full",
        "union", "intersect", "except", "all", "any", "some", "exists",
        "having", "limit", "offset", "fetch", "next", "rows", "only",
        "with", "recursive", "values", "set", "default", "unique",
        "distinct", "cast", "coalesce", "nullif", "true", "false",
        "asc", "desc", "nulls", "first", "last",
        "to", "add", "rename", "comment", "commit", "rollback", "begin", "start", "by"
    );

    @Override
    public String quoteName(String name) {
        if (needsQuoting(name)) {
            return '"' + name + '"';
        }
        return name;
    }

    @Override
    public String createTable(TableDef table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sb.append(quoteName(table.name())).append(" (\n");
        for (int i = 0; i < table.columns().size(); i++) {
            ColumnDef col = table.columns().get(i);
            sb.append("    ").append(col.toSql(this));
            if (i < table.columns().size() - 1) {
                sb.append(",\n");
            }
        }
        List<String> pks = table.primaryKeys();
        if (!pks.isEmpty()) {
            sb.append(",\n    PRIMARY KEY (");
            sb.append(pks.stream().map(this::quoteName).collect(Collectors.joining(", ")));
            sb.append(")");
        }
        sb.append("\n)");
        return sb.toString();
    }

    @Override
    public List<String> createIndexes(TableDef table) {
        List<String> ddls = new ArrayList<>();
        for (IndexDef idx : table.indexes()) {
            ddls.add(idx.toSql(this, table.name()));
        }
        return List.copyOf(ddls);
    }

    @Override
    public String addColumn(String tableName, ColumnDef column) {
        return "ALTER TABLE " + quoteName(tableName) + " " + column.toAlterSql(this);
    }

    @Override
    public String dropTable(String tableName) {
        return "DROP TABLE IF EXISTS " + quoteName(tableName);
    }

    @Override
    public String generatedClause() {
        return "GENERATED ALWAYS AS IDENTITY";
    }

    @Override
    public String defaultUUIDType() {
        return "UUID";
    }

    @Override
    public Set<String> existingTables(Database db) {
        try {
            List<String> tables = db.query(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?",
                effectiveSchema()
            ).list(String.class);
            return tables.stream()
                .filter(t -> t != null)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    @Override
    public Set<String> existingColumns(Database db, String tableName) {
        try {
            List<String> columns = db.query(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND TABLE_SCHEMA = ?",
                tableName.toUpperCase(), effectiveSchema()
            ).list(String.class);
            return columns.stream()
                .filter(c -> c != null)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    @Override
    public Set<String> existingIndexes(Database db, String tableName) {
        try {
            // H2: INFORMATION_SCHEMA.INDEXES 包含所有索引
            List<String> indexes = db.query(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = ? AND TABLE_SCHEMA = ?",
                tableName.toUpperCase(), effectiveSchema()
            ).list(String.class);
            return indexes.stream()
                .filter(i -> i != null)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    private static String effectiveSchema() {
        return "PUBLIC";
    }

    private static boolean needsQuoting(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (RESERVED.contains(name.toLowerCase())) {
            return true;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                if (!Character.isLetter(c) && c != '_') {
                    return true;
                }
            } else {
                if (!Character.isLetterOrDigit(c) && c != '_') {
                    return true;
                }
            }
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isUpperCase(name.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
