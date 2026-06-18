package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SQLite SQL dialect.
 *
 * <p>Uses double-quote quoting, {@code AUTOINCREMENT}, and {@code sqlite_master}
 * for schema introspection (SQLite has no {@code INFORMATION_SCHEMA}).
 */
public final class SqliteDialect implements Dialect {
    private static final Logger LOG = LoggerFactory.getLogger(SqliteDialect.class);

    private static final Set<String> RESERVED = Set.of(
        "user", "order", "group", "table", "select", "from", "where",
        "insert", "update", "delete", "create", "alter", "drop", "index",
        "primary", "key", "foreign", "references", "check", "constraint",
        "column", "view", "trigger", "case", "when", "then", "else", "end",
        "as", "on", "off", "like", "in", "is", "not", "null", "and", "or",
        "between", "join", "inner", "left", "right", "outer", "cross",
        "union", "intersect", "except", "all", "any", "some", "exists",
        "having", "limit", "offset", "with", "recursive", "values", "set",
        "default", "unique", "distinct", "cast", "coalesce", "nullif",
        "true", "false", "asc", "desc", "nulls", "first", "last",
        "to", "add", "rename", "commit", "rollback", "begin", "start", "by",
        "abort", "attach", "detach", "reindex", "release", "savepoint",
        "vacuum", "glob", "match", "regexp", "escape", "collate", "rowid"
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
        List<String> pks = table.primaryKeys();
        ColumnDef generatedPk = generatedPrimaryKey(table);
        if (generatedPk != null && pks.size() != 1) {
            throw new IllegalArgumentException(
                "SQLite AUTOINCREMENT requires a single primary key column"
            );
        }
        for (int i = 0; i < table.columns().size(); i++) {
            ColumnDef col = table.columns().get(i);
            sb.append("    ").append(renderColumn(col, generatedPk));
            if (i < table.columns().size() - 1) {
                sb.append(",\n");
            }
        }
        if (!pks.isEmpty() && generatedPk == null) {
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
        // SQLite does not support AUTOINCREMENT in ALTER TABLE ADD COLUMN
        String def = column.generated()
            ? column.toAlterSqlWithoutGenerated(this)
            : column.toAlterSql(this);
        return "ALTER TABLE " + quoteName(tableName) + " " + def;
    }

    @Override
    public String dropTable(String tableName) {
        return "DROP TABLE IF EXISTS " + quoteName(tableName);
    }

    @Override
    public String generatedClause() {
        return "AUTOINCREMENT";
    }

    @Override
    public String defaultUUIDType() {
        return "TEXT";
    }

    @Override
    public String defaultInstantType() {
        return "TEXT";
    }

    @Override
    public String defaultBinaryType() {
        return "BLOB";
    }

    @Override
    public Set<String> existingTables(Database db) {
        try {
            List<String> tables = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            ).list(String.class);
            return tables.stream()
                .filter(t -> t != null)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            LOG.warn("Failed to list existing tables", e);
            return Collections.emptySet();
        }
    }

    @Override
    public Set<String> existingColumns(Database db, String tableName) {
        try {
            List<String> columns = db.query(
                "SELECT name FROM pragma_table_info(?)", tableName
            ).list(String.class);
            return columns.stream()
                .filter(c -> c != null)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            LOG.warn("Failed to list existing columns for table '{}'", tableName, e);
            return Collections.emptySet();
        }
    }

    private static boolean needsQuoting(String name) {
        if (RESERVED.contains(name.toLowerCase(Locale.ROOT))) {
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

    private String renderColumn(ColumnDef column, ColumnDef generatedPk) {
        if (column == generatedPk) {
            return quoteName(column.name()) + " INTEGER PRIMARY KEY " + generatedClause();
        }
        return column.toSql(this);
    }

    private ColumnDef generatedPrimaryKey(TableDef table) {
        ColumnDef generatedPk = null;
        for (ColumnDef column : table.columns()) {
            if (!column.generated()) {
                continue;
            }
            if (!column.primaryKey()) {
                throw new IllegalArgumentException(
                    "SQLite AUTOINCREMENT columns must also be primary keys"
                );
            }
            if (generatedPk != null) {
                throw new IllegalArgumentException(
                    "SQLite AUTOINCREMENT requires a single generated primary key column"
                );
            }
            generatedPk = column;
        }
        return generatedPk;
    }
}
