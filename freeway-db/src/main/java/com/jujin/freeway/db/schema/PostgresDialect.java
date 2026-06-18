package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Locale;

/**
 * PostgreSQL / H2 (PostgreSQL compatibility mode) SQL dialect.
 * <p>
 * This is the default dialect used when no explicit dialect is configured.
 * It uses double-quote identifier quoting, {@code GENERATED ALWAYS AS IDENTITY}
 * for auto-increment, and queries {@code INFORMATION_SCHEMA} for schema
 * introspection.
 */
public final class PostgresDialect implements Dialect {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresDialect.class);

    /** Common SQL reserved words that always need quoting. */
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
    public Set<String> existingIndexes(Database db, String tableName) {
        try {
            List<String> indexes = db.query(
                "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
                effectiveSchema().toLowerCase(Locale.ROOT), tableName.toLowerCase(Locale.ROOT)
            ).list(String.class);
            return indexes.stream()
                .filter(i -> i != null)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            LOG.warn("Failed to list existing indexes for table '{}'", tableName, e);
            return Collections.emptySet();
        }
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
                .map(s -> s.toLowerCase(Locale.ROOT))
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
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND TABLE_SCHEMA = ?",
                tableName.toUpperCase(Locale.ROOT), effectiveSchema()
            ).list(String.class);
            return columns.stream()
                .filter(c -> c != null)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            LOG.warn("Failed to list existing columns for table '{}'", tableName, e);
            return Collections.emptySet();
        }
    }

    private static String effectiveSchema() {
        return "PUBLIC";
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
}
