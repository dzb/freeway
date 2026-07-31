package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MySQL / MariaDB SQL dialect.
 *
 * <p>Uses backtick quoting, {@code AUTO_INCREMENT}, and queries
 * {@code INFORMATION_SCHEMA} for schema introspection.
 *
 * <p>The upsert clause uses {@code ON DUPLICATE KEY UPDATE col = VALUES(col)},
 * which is compatible with MySQL 5.x through 8.0.x and all MariaDB versions.
 * For MySQL 8.0.20+, the {@code VALUES()} function is deprecated but still functional;
 * MySQL 9.0+ users should configure {@link PostgresDialect} or a custom dialect.
 */
public final class MySqlDialect implements Dialect {
    private static final Set<String> RESERVED = buildReserved(
        "status", "show", "describe", "explain", "use", "repeat", "loop", "leave",
        "iterate", "return", "while", "declare", "handler", "condition", "signal",
        "resignal", "get", "diagnostics", "sqlstate", "call", "do", "if", "for"
    );

    @Override
    public String dialectId() {
        return "mysql";
    }

    @Override
    public char quoteChar() {
        return '`';
    }

    @Override
    public boolean supportsIndexIfNotExists() {
        return false;
    }

    @Override
    public boolean supportsReturning() {
        return false;
    }

    @Override
    public boolean supportsOnConflict() {
        return false;
    }

    @Override
    public String upsertClause(List<String> conflictColumns, List<String> updateColumns) {
        List<String> updates = new ArrayList<>(updateColumns.size());
        for (String col : updateColumns) {
            String q = quoteName(col);
            updates.add(q + " = VALUES(" + q + ")");
        }
        return " ON DUPLICATE KEY UPDATE " + String.join(", ", updates);
    }

    @Override
    public String generatedClause() {
        return "AUTO_INCREMENT";
    }

    @Override
    public String defaultUUIDType() {
        return "VARCHAR(36)";
    }

    @Override
    public String defaultInstantType() {
        return "DATETIME(6)";
    }

    @Override
    public String defaultBinaryType() {
        return "LONGBLOB";
    }

    // ====================== schema introspection ======================

    @Override
    public Set<String> existingTables(Database db) {
        return querySet(db,
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE()");
    }

    @Override
    public Set<String> existingColumns(Database db, String tableName) {
        return querySet(db,
            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND TABLE_SCHEMA = DATABASE()",
            tableName);
    }

    @Override
    public Set<String> existingIndexes(Database db, String tableName) {
        return querySet(db,
            "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
            tableName);
    }

    @Override
    public Set<String> reservedWords() {
        return RESERVED;
    }

    private static Set<String> buildReserved(String... mysqlSpecific) {
        Set<String> words = new HashSet<>(Dialect.COMMON_RESERVED);
        words.addAll(Set.of(mysqlSpecific));
        return Set.copyOf(words);
    }
}
