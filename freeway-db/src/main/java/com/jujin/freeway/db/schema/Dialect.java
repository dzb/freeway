package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.List;
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

    /** Generates a DROP TABLE IF EXISTS statement. */
    String dropTable(String tableName);

    /** Returns the auto-increment clause, e.g. {@code "GENERATED ALWAYS AS IDENTITY"}. */
    String generatedClause();

    /** Returns the default SQL type for UUID columns. */
    String defaultUUIDType();

    /** Queries the set of existing table names in the database. */
    Set<String> existingTables(Database db);

    /** Queries the set of existing column names for a given table. */
    Set<String> existingColumns(Database db, String tableName);
}
