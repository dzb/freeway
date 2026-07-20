package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL dialect — responsible for DDL syntax differences and database metadata queries.
 * {@link PostgresDialect} is the primary implementation.
 *
 * <p>Implementors must provide: {@link #reservedWords()}, {@link #generatedClause()},
 * {@link #defaultUUIDType()}, {@link #existingTables(Database)},
 * {@link #existingColumns(Database, String)}.
 *
 * <p>All DDL-generation methods ({@link #createTable}, {@link #dropTable}, etc.) have
 * sensible defaults that produce correct SQL for most databases. Override where the
 * dialect's syntax differs (e.g. SQLite for {@link #createTable}).
 */
public interface Dialect {

    /** Common SQL reserved words shared by most databases. */
    Set<String> COMMON_RESERVED = Set.of(
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

    // ====================== identifier quoting ======================

    /**
     * Returns the dialect identifier used for registration and URL detection
     * (e.g. {@code "postgresql"}, {@code "mysql"}, {@code "sqlite"}, {@code "h2"}).
     */
    String dialectId();

    /** Returns the quote character for this dialect ({@code "} or {@code `}). */
    default char quoteChar() {
        return '"';
    }

    /**
     * Quotes an identifier if needed (reserved word, non-standard characters, uppercase).
     * Subclasses may override {@link #quoteChar()} instead of this method.
     */
    default String quoteName(String name) {
        if (needsQuoting(name)) {
            char q = quoteChar();
            return q + name + q;
        }
        return name;
    }

    /** Returns the set of reserved words for this dialect. */
    default Set<String> reservedWords() {
        return Set.of();
    }

    /**
     * Returns true if the given identifier needs quoting.
     */
    default boolean needsQuoting(String name) {
        if (name == null) {
            return false;
        }
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

    // ====================== DDL generation ======================

    /** Generates a CREATE TABLE IF NOT EXISTS statement. */
    default String createTable(TableDef table) {
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

    /** Generates standalone CREATE INDEX statements. */
    default List<String> createIndexes(TableDef table) {
        List<String> ddls = new ArrayList<>();
        for (IndexDef idx : table.indexes()) {
            ddls.add(idx.toSql(this, table.name()));
        }
        return List.copyOf(ddls);
    }

    /** Generates an ALTER TABLE ADD COLUMN statement. */
    default String addColumn(String tableName, ColumnDef column) {
        return "ALTER TABLE " + quoteName(tableName) + " " + column.toAlterSql(this);
    }

    /** Generates a DROP TABLE IF EXISTS statement. */
    default String dropTable(String tableName) {
        return "DROP TABLE IF EXISTS " + quoteName(tableName);
    }

    /**
     * Generates a TRUNCATE (or equivalent DELETE) statement.
     * Default: {@code TRUNCATE TABLE "t"}. Override for databases that need
     * {@code RESTART IDENTITY} (PostgreSQL) or {@code DELETE FROM} (SQLite).
     */
    default String truncateTable(String tableName) {
        return "TRUNCATE TABLE " + quoteName(tableName);
    }

    // ====================== DML clauses ======================

    /**
     * Returns the upsert suffix appended after {@code INSERT INTO t (cols) VALUES (vals)}.
     * Each dialect quotes column names internally; callers should pass raw (unquoted) names.
     *
     * @param conflictColumns raw primary-key column names
     * @param updateColumns   raw column names for the UPDATE SET assignments
     * @return e.g. {@code " ON CONFLICT (id) DO UPDATE SET "name" = EXCLUDED."name""}
     */
    default String upsertClause(List<String> conflictColumns, List<String> updateColumns) {
        String target = conflictColumns.isEmpty() ? ""
            : "(" + conflictColumns.stream().map(this::quoteName).collect(Collectors.joining(", ")) + ")";
        List<String> updates = new ArrayList<>(updateColumns.size());
        for (String col : updateColumns) {
            String q = quoteName(col);
            updates.add(q + " = EXCLUDED." + q);
        }
        return " ON CONFLICT " + target + " DO UPDATE SET " + String.join(", ", updates);
    }

    /** Whether {@code CREATE INDEX IF NOT EXISTS} is supported. */
    default boolean supportsIndexIfNotExists() {
        return true;
    }

    /** Whether {@code INSERT/UPDATE/DELETE ... RETURNING col} is supported. */
    default boolean supportsReturning() {
        return true;
    }

    /** Whether {@code INSERT ... ON CONFLICT ...} syntax is supported. */
    default boolean supportsOnConflict() {
        return true;
    }

    /** Returns the FOR UPDATE clause for row-level locking (without leading space). */
    default String forUpdateClause() {
        return "FOR UPDATE";
    }

    // ====================== type mappings ======================

    /** Returns the auto-increment clause, e.g. {@code "GENERATED ALWAYS AS IDENTITY"}. */
    String generatedClause();

    /**
     * Overrides the SQL type for a generated (auto-increment) column.
     * Default returns the original type unchanged. Override for dialects that
     * require a specific type for identity columns (e.g. SQLite requires {@code INTEGER}).
     */
    default String generatedTypeOverride(String sqlType, Class<?> javaType) {
        return sqlType;
    }

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

    // ====================== schema introspection ======================

    /**
     * Returns the effective schema name used for INFORMATION_SCHEMA queries.
     * Defaults to {@code "public"} (lowercase, compatible with PostgreSQL).
     */
    default String effectiveSchema() {
        return "public";
    }

    /** Queries the set of existing table names in the database. Results are lowercased. */
    Set<String> existingTables(Database db);

    /** Queries the set of existing column names for a given table. Results are lowercased. */
    Set<String> existingColumns(Database db, String tableName);

    /** Queries the set of existing index names for a given table. Results are lowercased. */
    default Set<String> existingIndexes(Database db, String tableName) {
        return Set.of();
    }

    /**
     * Helper for introspection queries: executes a SQL query, lowercases results,
     * and returns them as a set. On error, logs a warning and returns an empty set.
     */
    default Set<String> querySet(Database db, String sql, Object... params) {
        try {
            List<String> rows = db.query(sql, params).list(String.class);
            return rows.stream()
                .filter(r -> r != null)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        } catch (Exception e) {
            System.getLogger(Dialect.class.getName())
                .log(System.Logger.Level.WARNING, "Schema introspection query failed: " + sql, e);
            return Collections.emptySet();
        }
    }
}
