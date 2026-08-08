package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.dialect.Dialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * // Generate DDL only, no execution
 * String ddl = Schema.define(User.class);
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
     * @param db          database connection
     * @param entityTypes entity classes annotated with @Table, @Id, etc.
     * @return number of DDL statements executed
     * @throws SqlException if execution fails
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
     * @param db          database connection
     * @param dialect     SQL dialect for DDL generation
     * @param entityTypes entity classes annotated with @Table, @Id, etc.
     * @return number of DDL statements executed
     * @throws SqlException if execution fails
     */
    private static int ensure(Database db, Dialect dialect, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(dialect, "dialect");
        if (entityTypes == null || entityTypes.length == 0) {
            return 0;
        }

        SchemaGenerator gen = new SchemaGenerator(dialect);
        int executed = 0;

        Set<String> existingTables = new HashSet<>(dialect.existingTables(db));
        if (LOG.isDebugEnabled()) {
            LOG.debug("Existing tables in schema: {}", existingTables);
        }

        for (Class<?> type : entityTypes) {
            TableDef table = gen.define(type);
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
            Set<String> existingCols = dialect.existingColumns(db, tableName);
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
        for (Class<?> type : entityTypes) {
            TableDef table = gen.define(type);
            Set<String> existingIndexes = dialect.supportsIndexIfNotExists()
                ? Set.of()
                : dialect.existingIndexes(db, table.name());
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

}
