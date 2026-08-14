package com.jujin.freeway.db.dialect;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SQL dialect — syntax capabilities, DDL primitives, and database metadata
 * queries. {@link PostgresDialect} is the primary implementation.
 *
 * <p>Implementors must provide: {@link #generatedClause()},
 * {@link #defaultUUIDType()}, {@link #existingTables(Database)},
 * {@link #existingColumns(Database, String)}. ({@link #reservedWords()} has
 * an empty default; override it when the DB reserves words the framework
 * would otherwise emit unquoted.)
 *
 * <p>This interface declares <em>syntax features</em> only: identifier quoting,
 * identity/generated clauses, type mapping defaults, single-clause mappings
 * ({@link #upsertClause}, {@link #forUpdateClause}, {@link #offsetOnlyClause},
 * {@link #truncateTable}), capability flags, and introspection queries.
 * Multi-statement DDL <em>assembly</em> (CREATE TABLE, CREATE INDEX, ALTER,
 * DROP) is the {@code schema} package's responsibility — {@code Schema} and
 * {@code SchemaGenerator} consume these primitives to produce dialect-correct
 * DDL.
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

    /**
     * Returns the quote character used for <em>generated</em> DDL
     * ({@code "} or {@code `}). This is the dialect's primary identifier quote
     * — distinct from {@link #identifierQuoteChars()}, which declares every
     * quote character the SQL <em>scanner</em> accepts as input (a superset,
     * e.g. MySQL's ANSI_QUOTES mode).
     */
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
     * Builds the reserved-word set for a dialect: the {@link #COMMON_RESERVED}
     * words plus the dialect-specific ones. Shared by the built-in dialects;
     * custom dialects may use it too.
     */
    static Set<String> buildReserved(String... specific) {
        Set<String> words = new HashSet<>(COMMON_RESERVED);
        words.addAll(Set.of(specific));
        return Set.copyOf(words);
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
            if (Character.isUpperCase(c)) {
                return true;
            }
        }
        return false;
    }

    // ====================== SQL lexing ======================

    /**
     * Quote characters the SQL scanner accepts as identifier quoting (a
     * doubled character escapes, e.g. {@code ''} or {@code ``}). The scanner
     * never decides by itself which quoting style a database uses — the
     * dialect declares it.
     *
     * <p>This is the <em>input</em> side of quoting; {@link #quoteChar()} is
     * the single character used for <em>generated</em> DDL. ANSI default:
     * {@code "}. MySQL accepts {@code `} (and {@code "} under ANSI_QUOTES), so
     * {@link MySqlDialect} declares both.
     */
    default String identifierQuoteChars() {
        return "\"";
    }

    /**
     * Whether {@code #} starts a line comment. MySQL: yes. PostgreSQL: no —
     * {@code #} is the XOR operator there (and {@code #>} / {@code #>>} are
     * the jsonb path operators, which the scanner always exempts).
     */
    default boolean hashLineComments() {
        return false;
    }

    /**
     * Whether {@code [bracket]} identifier quoting is supported (SQL Server).
     * No built-in dialect uses it; kept for custom dialects.
     */
    default boolean bracketQuoting() {
        return false;
    }

    /**
     * Whether {@code $tag$...$tag$} dollar-quoted string literals are
     * supported (PostgreSQL). When false, {@code $name} is always parsed as a
     * named parameter.
     */
    default boolean dollarQuoting() {
        return false;
    }

    /**
     * Whether {@code E'...'} escape-string literals are supported
     * (PostgreSQL; backslash escapes the next character).
     */
    default boolean escapeStringPrefix() {
        return false;
    }

    /**
     * Whether a backslash escapes the next character inside <em>ordinary</em>
     * single-quoted string literals (MySQL/MariaDB: {@code 'it\'s'} is the
     * string {@code it's}). The SQL standard only defines {@code ''}
     * doubling; PostgreSQL handles backslash escapes via {@code E'...'}
     * literals ({@link #escapeStringPrefix()}) — in a regular PG string a
     * backslash is literal text ({@code standard_conforming_strings=on}), so
     * this defaults to {@code false}.
     */
    default boolean backslashEscapesStrings() {
        return false;
    }

    // ====================== DDL syntax features ======================

    /**
     * Boolean capabilities on this interface follow two conventions:
     * <ul>
     *   <li>{@code supportsX(...)} — whether the database has a SQL feature
     *       ({@link #supportsIndexIfNotExists()}, {@link #supportsReturning()},
     *       {@link #supportsOnConflict()}).</li>
     *   <li>noun-phrase booleans — the <em>shape</em> of a syntax construct
     *       ({@link #generatedPrimaryKeyInline()}, {@link #dropTableCascade()},
     *       {@link #alterAddColumnNotNull()}, and the lexer capabilities in
     *       the next section).</li>
     * </ul>
     */

    /**
     * Whether a generated primary key column is declared inline on the column
     * itself — SQLite: {@code "id" INTEGER PRIMARY KEY AUTOINCREMENT} — instead
     * of via a trailing {@code PRIMARY KEY (...)} table clause. DDL assembly
     * itself lives in {@code schema} (it consumes this capability).
     */
    default boolean generatedPrimaryKeyInline() {
        return false;
    }

    /** Whether {@code DROP TABLE} appends {@code CASCADE} (PostgreSQL). */
    default boolean dropTableCascade() {
        return false;
    }

    /**
     * Whether {@code ALTER TABLE ADD COLUMN} may carry a {@code NOT NULL}
     * constraint. SQLite cannot add NOT NULL columns without a DEFAULT, so it
     * returns false and the constraint is dropped from the generated DDL.
     */
    default boolean alterAddColumnNotNull() {
        return true;
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

    /**
     * Whether DDL statements participate in transactions. PostgreSQL, H2, and
     * SQLite support transactional DDL; MySQL/MariaDB implicitly commit on
     * every DDL statement, so a migration that mixes DDL with the checksum
     * INSERT can never be applied atomically there (the DDL is committed but
     * the checksum row is lost, so the next startup re-runs the DDL and fails).
     */
    default boolean supportsTransactionalDdl() {
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

    /**
     * Returns the pagination clause for an OFFSET without a LIMIT.
     * MySQL and SQLite reject a bare {@code OFFSET n}, so they override this
     * with an "unlimited" LIMIT; the default follows the SQL standard
     * ({@code LIMIT ALL OFFSET n}, accepted by PostgreSQL and H2).
     */
    default String offsetOnlyClause(long offset) {
        return "LIMIT ALL OFFSET " + offset;
    }

    // ====================== type mappings ======================

    /** Returns the auto-increment clause, e.g. {@code "GENERATED BY DEFAULT AS IDENTITY"}. */
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
     * Helper for introspection queries: executes a SQL query, lowercases
     * results, and returns them as a set.
     *
     * <p>An introspection failure throws {@link SqlException} — it must never
     * be silently read as "the database is empty", because callers such as
     * {@code Schema} would then generate misleading DDL (duplicate index
     * creation, re-CREATE of existing tables) against an unknown current
     * state. {@code Schema.ensure} catches this and skips the affected DDL
     * phase with a warning.
     */
    default Set<String> querySet(Database db, String sql, Object... params) {
        try {
            List<String> rows = db.query(sql, params).list(String.class);
            return rows.stream()
                .filter(r -> r != null)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        } catch (RuntimeException e) {
            throw new SqlException(
                "Schema introspection query failed: " + sql + " — cannot verify "
                    + "current database state", e);
        }
    }
}
