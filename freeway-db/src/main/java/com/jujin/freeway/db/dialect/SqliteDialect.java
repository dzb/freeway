package com.jujin.freeway.db.dialect;

import com.jujin.freeway.db.Database;
import java.util.Set;

/**
 * SQLite SQL dialect.
 *
 * <p>Uses double-quote quoting, {@code AUTOINCREMENT}, and {@code sqlite_master}
 * for schema introspection. SQLite has no TRUNCATE, so {@link #truncateTable}
 * generates {@code DELETE FROM}, which does <b>not</b> reset the auto-increment
 * counter (users who need that must delete the row from
 * {@code sqlite_sequence} manually). SQLite has no {@code FOR UPDATE}, so
 * {@link #forUpdateClause} returns an empty string.
 *
 * <p>SQLite 3.35.0+ supports {@code RETURNING} — {@link #supportsReturning()}
 * returns {@code true}.
 */
public final class SqliteDialect implements Dialect {
    private static final Set<String> RESERVED = Dialect.buildReserved(
        "abort", "attach", "detach", "reindex", "release", "savepoint",
        "vacuum", "glob", "match", "regexp", "escape", "collate", "rowid"
    );

    @Override
    public String dialectId() {
        return "sqlite";
    }

    @Override
    public String identifierQuoteChars() {
        // SQLite accepts ANSI double quotes and MySQL-style backticks.
        return "\"`";
    }

    @Override
    public boolean generatedPrimaryKeyInline() {
        // SQLite requires the generated PK declared on the column itself:
        // "id" INTEGER PRIMARY KEY AUTOINCREMENT
        return true;
    }

    @Override
    public boolean alterAddColumnNotNull() {
        // SQLite ALTER TABLE ADD COLUMN cannot add NOT NULL without a DEFAULT.
        return false;
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
    public String offsetOnlyClause(long offset) {
        // SQLite rejects a bare OFFSET; LIMIT -1 means "no limit".
        return "LIMIT -1 OFFSET " + offset;
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
}
