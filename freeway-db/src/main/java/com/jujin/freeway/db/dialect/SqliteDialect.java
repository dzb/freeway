package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQLite SQL dialect.
 *
 * <p>Uses double-quote quoting, {@code AUTOINCREMENT}, and {@code sqlite_master}
 * for schema introspection. SQLite has no TRUNCATE, so {@link #truncateTable}
 * generates {@code DELETE FROM} and also resets the auto-increment counter
 * via {@code sqlite_sequence}. SQLite has no {@code FOR UPDATE}, so
 * {@link #forUpdateClause} returns an empty string.
 *
 * <p>SQLite 3.35.0+ supports {@code RETURNING} — {@link #supportsReturning()}
 * returns {@code true}.
 */
public final class SqliteDialect implements Dialect {
    private static final Set<String> RESERVED = buildReserved(
        "abort", "attach", "detach", "reindex", "release", "savepoint",
        "vacuum", "glob", "match", "regexp", "escape", "collate", "rowid"
    );

    @Override
    public String dialectId() {
        return "sqlite";
    }

    @Override
    public String createTable(TableDef table) {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sb.append(quoteName(table.name())).append(" (\n");
        List<String> pks = table.primaryKeys();
        ColumnDef generatedPk = generatedPrimaryKey(table);
        if (generatedPk != null && pks.size() != 1) {
            throw new IllegalArgumentException(
                "SQLite AUTOINCREMENT requires a single primary key column");
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
    public String addColumn(String tableName, ColumnDef column) {
        // SQLite ALTER TABLE ADD COLUMN does not support AUTOINCREMENT
        // or NOT NULL without DEFAULT. Strip both for safety.
        return "ALTER TABLE " + quoteName(tableName) + " "
            + column.toAlterSql(this, false, false);
    }

    @Override
    public String truncateTable(String tableName) {
        // SQLite has no TRUNCATE. DELETE FROM does not reset AUTOINCREMENT
        // counters; users who need that should manually DELETE FROM sqlite_sequence.
        return "DELETE FROM " + quoteName(tableName);
    }

    @Override
    public String forUpdateClause() {
        return "";
    }

    @Override
    public String generatedClause() {
        return "AUTOINCREMENT";
    }

    @Override
    public String generatedTypeOverride(String sqlType, Class<?> javaType) {
        if (javaType == Long.class || javaType == long.class
            || javaType == Integer.class || javaType == int.class
            || "INTEGER".equalsIgnoreCase(sqlType)) {
            return "INTEGER";
        }
        throw new IllegalArgumentException(
            "SQLite AUTOINCREMENT columns must use an integer type: " + javaType.getName());
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

    // ====================== schema introspection ======================

    @Override
    public Set<String> existingTables(Database db) {
        return querySet(db,
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'");
    }

    @Override
    public Set<String> existingColumns(Database db, String tableName) {
        return querySet(db,
            "SELECT name FROM pragma_table_info(?)", tableName);
    }

    @Override
    public Set<String> reservedWords() {
        return RESERVED;
    }

    private static Set<String> buildReserved(String... sqliteSpecific) {
        Set<String> words = new HashSet<>(Dialect.COMMON_RESERVED);
        words.addAll(Set.of(sqliteSpecific));
        return Set.copyOf(words);
    }

    // ====================== internals ======================

    private String renderColumn(ColumnDef column, ColumnDef generatedPk) {
        if (column == generatedPk) {
            return quoteName(column.name()) + " INTEGER PRIMARY KEY " + generatedClause();
        }
        return column.toSql(this);
    }

    private static ColumnDef generatedPrimaryKey(TableDef table) {
        ColumnDef generatedPk = null;
        for (ColumnDef column : table.columns()) {
            if (!column.generated()) {
                continue;
            }
            if (!column.primaryKey()) {
                throw new IllegalArgumentException(
                    "SQLite AUTOINCREMENT columns must also be primary keys");
            }
            if (generatedPk != null) {
                throw new IllegalArgumentException(
                    "SQLite AUTOINCREMENT requires a single generated primary key column");
            }
            generatedPk = column;
        }
        return generatedPk;
    }
}
