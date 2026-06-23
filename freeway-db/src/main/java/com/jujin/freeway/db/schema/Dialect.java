package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL dialect — responsible for DDL syntax differences and database metadata queries.
 * {@link PostgresDialect} is the primary implementation, suitable for PostgreSQL and
 * H2 in PostgreSQL mode.
 */
public interface Dialect {

    /** Quotes an identifier (table or column name). */
    String quoteName(String name);

    /** Generates a CREATE TABLE statement. */
    String createTable(TableDef table);

    /** Generates standalone CREATE INDEX statements. */
    List<String> createIndexes(TableDef table);

    /** Generates an ALTER TABLE ADD COLUMN statement. */
    String addColumn(String tableName, ColumnDef column);

    /** Returns the set of reserved words for this dialect. */
    default Set<String> reservedWords() {
        return Set.of();
    }

    /**
     * Returns true if the given identifier needs quoting (is a reserved word
     * or contains non-standard characters).
     */
    default boolean needsQuoting(String name) {
        if (reservedWords().contains(name.toLowerCase(Locale.ROOT))) {
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

    /** Generates a DROP TABLE IF EXISTS statement. */
    String dropTable(String tableName);

    /** Whether {@code CREATE INDEX IF NOT EXISTS} is supported. */
    default boolean supportsIndexIfNotExists() {
        return true;
    }

    /** Returns the set of existing index names for a given table. */
    default Set<String> existingIndexes(Database db, String tableName) {
        return Set.of();
    }

    /** Returns the auto-increment clause, e.g. {@code "GENERATED ALWAYS AS IDENTITY"}. */
    String generatedClause();

    /** Returns the SQL type for UUID columns. */
    String defaultUUIDType();

    /** Returns the SQL type for timestamp-with-timezone columns. */
    default String defaultInstantType() {
        return "TIMESTAMP WITH TIME ZONE";
    }

    /** Returns the SQL type for binary / BLOB columns. */
    default String defaultBinaryType() {
        return "BYTEA";
    }

    /** Queries the set of existing table names in the database. */
    Set<String> existingTables(Database db);

    /** Queries the set of existing column names for a given table. */
    Set<String> existingColumns(Database db, String tableName);
}
