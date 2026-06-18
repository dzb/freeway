package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.SqlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
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
 * Schema.ensure(db, new PostgresDialect(), User.class, Post.class);
 *
 * // Drop tables
 * Schema.drop(db, new PostgresDialect(), User.class);
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
     * Generates a CREATE TABLE DDL string for the given entity type.
     * Uses PostgresDialect by default (suitable for PostgreSQL / H2).
     */
    public static String define(Class<?> entityType) {
        return new SchemaGenerator(new PostgresDialect()).generate(entityType);
    }

    /**
     * Generates CREATE TABLE DDL for multiple entity types.
     * Uses PostgresDialect by default (suitable for PostgreSQL / H2).
     */
    public static List<String> defineAll(Class<?>... entityTypes) {
        return new SchemaGenerator(new PostgresDialect()).generateAll(entityTypes);
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
    public static int ensure(Database db, Dialect dialect, Class<?>... entityTypes) {
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
                String ddl = dialect.createTable(table);
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
                    String alter = dialect.addColumn(tableName, col);
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
     * Drops tables for the given entity types using the specified dialect.
     */
    public static void drop(Database db, Dialect dialect, Class<?>... entityTypes) {
        Objects.requireNonNull(db, "db");
        Objects.requireNonNull(dialect, "dialect");
        if (entityTypes == null || entityTypes.length == 0) {
            return;
        }
        SchemaGenerator gen = new SchemaGenerator(dialect);
        for (Class<?> type : entityTypes) {
            TableDef table = gen.define(type);
            LOG.info("Dropping table: {}", table.name());
            db.execute(dialect.dropTable(table.name()));
        }
    }

}
