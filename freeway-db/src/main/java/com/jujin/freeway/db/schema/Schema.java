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
 *   <li>Validate only reports the same gaps without applying anything (see {@link #validate})</li>
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
     * @return number of <b>schema changes</b> applied — tables created and
     *         columns added. <b>Indexes are executed but not counted</b>: they
     *         are the companion of a table rather than a change to it, and a
     *         second {@code ensure()} over an unchanged entity must report 0
     *         even when it re-asserts the indexes. A caller that needs "was
     *         there work to do" gets a truthful answer; a caller counting
     *         statements should not read this as one.
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
        List<Unit> units = new ArrayList<>(entityTypes.length);
        for (Class<?> type : entityTypes) {
            units.add(new Unit(gen.define(type), type.getSimpleName()));
        }

        int executed = 0;
        for (Drift drift : inspect(db, dialect, gen, units, false)) {
            switch (drift.kind()) {
                case TABLE -> {
                    LOG.info("Creating table: {}", drift.table());
                    db.execute(drift.sql());
                    executed++;
                }
                case COLUMN -> {
                    if (drift.sql() == null) {
                        throw new SqlException(
                            "Cannot add column '" +
                                drift.detail() +
                                "' to existing table " +
                                drift.table() +
                                " — adding key/identity columns to an existing table " +
                                "requires a table rebuild; only nullable plain columns " +
                                "can be added via ALTER"
                        );
                    }
                    LOG.info("Adding column: {}.{}", drift.table(), drift.detail());
                    db.execute(drift.sql());
                    executed++;
                }
                case INDEX -> {
                    LOG.info("Ensuring index on {}", drift.table());
                    db.execute(drift.sql());
                }
            }
        }

        if (executed > 0) {
            LOG.info("AutoMigrate applied {} change(s)", executed);
        }
        return executed;
    }

    /**
     * Compares the declared entities against the live database without applying
     * anything: every missing table, column or index is returned as a drift
     * line naming the entity that declares it. Empty means the entities and
     * the database agree.
     *
     * <p>Read-only — safe on dialects without transactional DDL and inside a
     * transaction. Existence only: column types are not compared, so a drifted
     * type still needs a human-written migration.
     *
     * @param db          database connection
     * @param entityTypes entity classes annotated with @Table, @Id, etc.
     * @return drift descriptions, empty when nothing is missing
     */
    public static List<String> validate(Database db, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        if (entityTypes == null || entityTypes.length == 0) {
            return List.of();
        }
        Dialect dialect = db.dialect();
        SchemaGenerator gen = new SchemaGenerator(dialect);
        List<Unit> units = new ArrayList<>(entityTypes.length);
        for (Class<?> type : entityTypes) {
            units.add(new Unit(gen.define(type), type.getSimpleName()));
        }
        List<String> drift = new ArrayList<>();
        for (Drift drifted : inspect(db, dialect, gen, units, true)) {
            drift.add(describe(drifted));
        }
        return drift;
    }

    private static String describe(Drift drift) {
        return switch (drift.kind()) {
            case TABLE ->
                "table '" + drift.table() + "' is missing (entity " + drift.source() + " declares it)";
            case COLUMN ->
                "table '" + drift.table() + "' is missing column '" + drift.detail()
                    + "' (entity " + drift.source() + " declares it)";
            case INDEX ->
                "table '" + drift.table() + "' is missing index '" + drift.detail()
                    + "' (entity " + drift.source() + " declares it)";
        };
    }

    private enum DriftKind {
        TABLE, COLUMN, INDEX
    }

    /**
     * One gap between the declared entities and the live database, plus the
     * DDL that would close it — null when no automatic DDL exists (a
     * key/identity column on an existing table needs a rebuild, not an
     * ALTER).
     */
    private record Drift(DriftKind kind, String table, String detail, String source, String sql) {
    }

    /** A table definition with the entity that declares it, for drift messages. */
    private record Unit(TableDef table, String source) {
    }

    /**
     * The gap between the declared entities and the live database, computed
     * without applying anything: {@link #ensure} executes it, {@link #validate}
     * reports it.
     *
     * @param verifyIndexes whether index existence is always introspected.
     *        {@code ensure} passes false to keep its standing behavior (dialects
     *        with {@code IF NOT EXISTS} rely on idempotent DDL instead);
     *        {@code validate} passes true because it has no DDL to lean on.
     */
    private static List<Drift> inspect(
        Database db, Dialect dialect, SchemaGenerator gen, List<Unit> units, boolean verifyIndexes
    ) {
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
            return List.of();
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("Existing tables in schema: {}", existingTables);
        }

        List<Drift> drifts = new ArrayList<>();
        for (Unit unit : units) {
            TableDef table = unit.table();
            String tableName = table.name();
            String normalizedTableName = tableName.toLowerCase(Locale.ROOT);

            if (!existingTables.contains(normalizedTableName)) {
                drifts.add(new Drift(DriftKind.TABLE, tableName,
                    "table is missing", unit.source(), gen.generateTable(table)));
                existingTables.add(normalizedTableName);
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
                if (existingCols.contains(col.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (col.primaryKey() || col.generated()) {
                    // ALTER TABLE ADD COLUMN cannot carry a primary key or an
                    // identity/generated clause on MySQL (error 1075) or
                    // SQLite (constraints silently stripped). PostgreSQL/H2
                    // can ALTER-add identity columns, but the guard is
                    // uniform: a schema evolution that adds a key column
                    // deserves an explicit rebuild, not dialect-dependent
                    // behavior.
                    drifts.add(new Drift(DriftKind.COLUMN, tableName,
                        col.name(), unit.source(), null));
                    continue;
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
                drifts.add(new Drift(DriftKind.COLUMN, tableName,
                    col.name(), unit.source(), alter));
            }
        }

        // Indexes: dialects that do not support IF NOT EXISTS must skip existing indexes.
        for (Unit unit : units) {
            TableDef table = unit.table();
            Set<String> existingIndexes;
            if (!verifyIndexes && dialect.supportsIndexIfNotExists()) {
                existingIndexes = Set.of();
            } else {
                try {
                    existingIndexes = dialect.existingIndexes(db, table.name());
                } catch (SqlException e) {
                    // An introspection failure (e.g. pg_indexes absent on an
                    // H2-in-PostgreSQL-mode database) must not be read as "no
                    // indexes exist" — ensure would re-create every index as
                    // duplicate DDL, and validate would report fabricated drift
                    // and fail startup. Skip this table's indexes either way.
                    LOG.warn(
                        "Schema introspection failed to list indexes of table '{}'"
                            + " — skipping the index check for this table: {}",
                        table.name(), e.getMessage());
                    continue;
                }
            }
            for (IndexDef index : table.indexes()) {
                if (!existingIndexes.isEmpty() &&
                    existingIndexes.contains(index.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                drifts.add(new Drift(DriftKind.INDEX, table.name(),
                    index.name(), unit.source(), index.toSql(dialect, table.name())));
            }
        }
        return drifts;
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
