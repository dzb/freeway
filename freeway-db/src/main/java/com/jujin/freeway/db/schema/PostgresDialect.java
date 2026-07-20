package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.Set;
import java.util.Locale;

/**
 * PostgreSQL / H2 (PostgreSQL compatibility mode) SQL dialect.
 *
 * <p>Uses double-quote identifier quoting, {@code GENERATED ALWAYS AS IDENTITY}
 * for auto-increment, and queries {@code INFORMATION_SCHEMA} /
 * {@code pg_indexes} for schema introspection.
 */
public class PostgresDialect implements Dialect {

    @Override
    public String dialectId() {
        return "postgresql";
    }

    @Override
    public String truncateTable(String tableName) {
        return "TRUNCATE TABLE " + quoteName(tableName) + " RESTART IDENTITY";
    }

    @Override
    public String dropTable(String tableName) {
        return "DROP TABLE IF EXISTS " + quoteName(tableName) + " CASCADE";
    }

    @Override
    public String generatedClause() {
        return "GENERATED ALWAYS AS IDENTITY";
    }

    @Override
    public String defaultUUIDType() {
        return "UUID";
    }

    // ====================== schema introspection ======================

    @Override
    public Set<String> existingTables(Database db) {
        return querySet(db,
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE UPPER(TABLE_SCHEMA) = UPPER(?) AND TABLE_TYPE = 'BASE TABLE'",
            effectiveSchema());
    }

    @Override
    public Set<String> existingColumns(Database db, String tableName) {
        return querySet(db,
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE UPPER(TABLE_NAME) = ? AND UPPER(TABLE_SCHEMA) = UPPER(?)",
            tableName.toUpperCase(Locale.ROOT), effectiveSchema());
    }

    @Override
    public Set<String> existingIndexes(Database db, String tableName) {
        return querySet(db,
            "SELECT indexname FROM pg_indexes WHERE schemaname = ? AND tablename = ?",
            effectiveSchema(), tableName.toLowerCase(Locale.ROOT));
    }

    @Override
    public Set<String> reservedWords() {
        return Dialect.COMMON_RESERVED;
    }
}
