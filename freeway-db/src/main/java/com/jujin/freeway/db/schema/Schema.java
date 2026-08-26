package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.dialect.Dialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/**
 * Database schema utility — auto-generates and migrates tables from entity classes.
 *
 * <h3>Quick start</h3>
 * <pre>{@code
 * // Generate DDL only, no execution (the dialect is an explicit choice —
 * // no Database is bound during DDL generation)
 * String ddl = Schema.define(new PostgresDialect(), User.class);
 *
 * // AutoMigrate: create tables + add missing columns (never drops or alters)
 * Schema.ensure(db, User.class, Post.class);
 *
 * // Drop tables
 * Schema.drop(db, User.class);
 * }</pre>
 *
 * <h3>AutoMigrate strategy</h3>
 * <ul>
 *   <li>Table missing → {@code CREATE TABLE IF NOT EXISTS} + dialect-specific index handling</li>
 *   <li>Table exists, column missing → {@code ALTER TABLE ADD COLUMN}</li>
 *   <li>Table exists, index missing → {@code CREATE INDEX IF NOT EXISTS} or an explicit existence check</li>
 *   <li>Never drops existing columns/indexes or alters existing column types</li>
 * </ul>
 *
 * <h3>Supported annotations</h3>
 * <ul>
 *   <li>{@link Table @Table} — table name override</li>
 *   <li>{@link Column @Column} — column name, type, nullability override</li>
 *   <li>{@link Id @Id} — primary key</li>
 *   <li>{@link Generated @Generated} — auto-increment column</li>
 *   <li>{@link Transient @Transient} — exclude field</li>
 *   <li>{@link Index @Index} — index (supports composite and unique)</li>
 * </ul>
 * Also automatically recognizes validation annotations from commons
 * ({@code @NotNull}, {@code @NotBlank}, {@code @Size}).
 */
public final class Schema {
    private static final Logger LOG = LoggerFactory.getLogger(Schema.class);

    private Schema() {
    }

    /**
     * Generates a CREATE TABLE DDL string for the given entity type using a
     * specific dialect. DDL generation has no {@link Database} to derive a
     * dialect from, so the dialect is an explicit choice.
     */
    public static String define(Dialect dialect, Class<?> entityType) {
        return new SchemaGenerator(dialect).generate(entityType);
    }

    /**
     * Generates CREATE TABLE DDL for multiple entity types using a specific dialect.
     */
    public static List<String> defineAll(Dialect dialect, Class<?>... entityTypes) {
        return new SchemaGenerator(dialect).generateAll(entityTypes);
    }

    /**
     * Ensures tables and columns exist for the given entity types using the
     * specified dialect. Creates missing tables, adds missing columns, and
     * creates missing indexes. Never drops or alters existing columns.
     *
     * <p><b>Not transactional.</b> {@code ensure()} executes each DDL
     * statement on its own connection/statement and never opens a
     * transaction, so a failure mid-way leaves a partially applied schema.
     * On databases without transactional DDL (MySQL/MariaDB — see
     * {@link Dialect#supportsTransactionalDdl()}) every DDL statement
     * implicitly commits, so calling {@code ensure()} inside a user
     * transaction would silently commit that transaction's pending work;
     * {@code ensure()} refuses to run in that situation with a
     * {@link SqlException}. On transactional-DDL databases (PostgreSQL, H2,
     * SQLite) wrapping {@code ensure()} in a transaction is safe and rolls
     * the whole schema back on failure.
     *
     * <p>If schema introspection fails (e.g. an emulated database without
     * {@code pg_indexes}), the corresponding DDL phase is skipped with a
     * warning rather than treating the database as empty and generating
     * misleading DDL.
     *
     * @param db          database connection
     * @param entityTypes entity classes annotated with @Table, @Id, etc.
     * @return number of DDL statements executed
     * @throws SqlException if execution fails, or when called inside a
     *                      transaction on a database without transactional DDL
     */
    public static int ensure(Database db, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        return ensure(db, db.dialect(), entityTypes);
    }

    /**
     * Ensures tables and columns exist for the given entity types using the
     * specified dialect. Creates missing tables, adds missing columns, and
     * creates missing indexes. Never drops or alters existing columns.
     *
     * <p>Not transactional — see {@link #ensure(Database, Class[])}.
     *
     * @param db          database connection
     * @param dialect     SQL dialect for DDL generation
     * @param entityTypes entity classes annotated with @Table, @Id, etc.
     * @return number of DDL statements executed
     * @throws SqlException if execution fails, or when called inside a
     *                      transaction on a database without transactional DDL
     */
    private static int ensure(Database db, Dialect dialect, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(dialect, "dialect");
        if (entityTypes == null || entityTypes.length == 0) {
            return 0;
        }
        requireTransactionalDdlSafe(db, dialect, "ensure");

        SchemaGenerator gen = new SchemaGenerator(dialect);
        int executed = 0;

        // Introspection failures must not be read as "the database is empty":
        // that would generate CREATE TABLE / CREATE INDEX against an unknown
        // current state (e.g. pg_indexes is absent on H2 in PostgreSQL mode).
        // Skip the affected DDL phase with a warning instead.
        Set<String> existingTables;
        try {
            existingTables = new HashSet<>(dialect.existingTables(db));
        } catch (SqlException e) {
            LOG.warn(
                "Schema introspection failed to list existing tables (dialect '{}')"
                    + " — skipping schema auto-DDL: {}",
                dialect.dialectId(), e.getMessage());
            return 0;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Existing tables in schema: {}", existingTables);
        }

        List<TableDef> tableDefs = new ArrayList<>(entityTypes.length);
        for (Class<?> type : entityTypes) {
            tableDefs.add(gen.define(type));
        }

        for (TableDef table : tableDefs) {
            String tableName = table.name();
            String normalizedTableName = tableName.toLowerCase(Locale.ROOT);

            if (!existingTables.contains(normalizedTableName)) {
                String ddl = gen.generateTable(table);
                LOG.info("Creating table: {}", tableName);
                db.execute(ddl);
                existingTables.add(normalizedTableName);
                executed++;
                continue;
            }

            // Table exists — check for missing columns
            Set<String> existingCols;
            try {
                existingCols = dialect.existingColumns(db, tableName);
            } catch (SqlException e) {
                LOG.warn(
                    "Schema introspection failed to list columns of table '{}'"
                        + " — skipping column additions for this table: {}",
                    tableName, e.getMessage());
                continue;
            }
            if (LOG.isDebugEnabled()) {
                LOG.debug("Existing columns for {}: {}", tableName, existingCols);
            }

            for (ColumnDef col : table.columns()) {
                if (!existingCols.contains(col.name().toLowerCase(Locale.ROOT))) {
                    // ALTER TABLE ADD COLUMN cannot carry a primary key or an
                    // identity/generated clause on MySQL (error 1075) or
                    // SQLite (constraints silently stripped). PostgreSQL/H2
                    // can ALTER-add identity columns, but the guard is
                    // uniform: a schema evolution that adds a key column
                    // deserves an explicit rebuild, not dialect-dependent
                    // behavior.
                    if (col.primaryKey() || col.generated()) {
                        throw new SqlException(
                            "Cannot add column '" +
                                col.name() +
                                "' to existing table " +
                                tableName +
                                " — adding key/identity columns to an existing table " +
                                "requires a table rebuild; only nullable plain columns " +
                                "can be added via ALTER"
                        );
                    }
                    // SQLite cannot add NOT NULL without a DEFAULT — strip the
                    // constraint there per the dialect's declaration.
                    String alter = "ALTER TABLE " +
                        dialect.quoteName(tableName) +
                        " " +
                        col.toAlterSql(
                            dialect,
                            dialect.alterAddColumnNotNull(),
                            true
                        );
                    LOG.info("Adding column: {}.{}", tableName, col.name());
                    db.execute(alter);
                    executed++;
                }
            }
        }

        // Indexes: dialects that do not support IF NOT EXISTS must skip existing indexes.
        for (TableDef table : tableDefs) {
            Set<String> existingIndexes;
            if (dialect.supportsIndexIfNotExists()) {
                existingIndexes = Set.of();
            } else {
                try {
                    existingIndexes = dialect.existingIndexes(db, table.name());
                } catch (SqlException e) {
                    // An introspection failure (e.g. pg_indexes absent on an
                    // H2-in-PostgreSQL-mode database) must not be read as "no
                    // indexes exist" — that would re-create every index as
                    // duplicate DDL. Skip index creation for this table.
                    LOG.warn(
                        "Schema introspection failed to list indexes of table '{}'"
                            + " — skipping index creation for this table: {}",
                        table.name(), e.getMessage());
                    continue;
                }
            }
            for (IndexDef index : table.indexes()) {
                if (!existingIndexes.isEmpty() &&
                    existingIndexes.contains(index.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                LOG.info("Ensuring index on {}", table.name());
                db.execute(index.toSql(dialect, table.name()));
            }
        }

        if (executed > 0) {
            LOG.info("AutoMigrate applied {} change(s)", executed);
        }
        return executed;
    }

    /**
     * Drops tables for the given entity types using the database's dialect.
     * Like {@link #ensure(Database, Class[])}, drop is not transactional and
     * refuses to run inside a transaction on databases without transactional
     * DDL (MySQL/MariaDB).
     */
    public static void drop(Database db, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        drop(db, db.dialect(), entityTypes);
    }

    /**
     * Drops tables for the given entity types using the specified dialect.
     */
    private static void drop(Database db, Dialect dialect, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(dialect, "dialect");
        if (entityTypes == null || entityTypes.length == 0) {
            return;
        }
        requireTransactionalDdlSafe(db, dialect, "drop");
        SchemaGenerator gen = new SchemaGenerator(dialect);
        for (Class<?> type : entityTypes) {
            TableDef table = gen.define(type);
            LOG.info("Dropping table: {}", table.name());
            String ddl = "DROP TABLE IF EXISTS " +
                dialect.quoteName(table.name()) +
                (dialect.dropTableCascade() ? " CASCADE" : "");
            db.execute(ddl);
        }
    }

    /**
     * Rejects schema DDL that would silently commit a surrounding user
     * transaction: on dialects without transactional DDL (MySQL/MariaDB) every
     * DDL statement implicitly commits, so running {@code ensure()}/{@code drop()}
     * inside a transaction would commit its pending work mid-way. Databases
     * with transactional DDL (PostgreSQL, H2, SQLite) are safe and allowed.
     */
    private static void requireTransactionalDdlSafe(
        Database db,
        Dialect dialect,
        String operation
    ) {
        if (db.inTransaction() && !dialect.supportsTransactionalDdl()) {
            throw new SqlException(
                "Schema." + operation + "() must not run inside a transaction on "
                    + "dialect '" + dialect.dialectId() + "' — DDL statements "
                    + "implicitly commit the surrounding transaction there; run "
                    + "schema DDL before opening the transaction, or use a "
                    + "transactional-DDL database (PostgreSQL, H2, SQLite)"
            );
        }
    }

}
