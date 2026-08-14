package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.BatchQuery;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseBuilder;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.ExecuteResult;
import com.jujin.freeway.db.IsolationLevel;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.SqlException;
import com.jujin.freeway.db.Transactional;
import com.jujin.freeway.db.dialect.Dialect;
import com.jujin.freeway.db.dialect.PostgresDialect;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema introspection failures must not be read as "the database is empty":
 * that would generate duplicate index DDL (or re-CREATE existing tables)
 * against an unknown current state. ensure() skips the affected DDL phase
 * with a warning instead.
 */
class SchemaIntrospectionFailureTest {

    @Test
    void h2PostgresModeSkipsIndexCreationWhenPgIndexesIsMissing() {
        // PostgresDialect.existingIndexes queries pg_indexes, which does not
        // exist in H2's PostgreSQL compatibility mode. The failure must skip
        // the index DDL phase rather than treat the table as index-free.
        String dbName = "fw_schema_intro_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .dialect(new PgIndexesDialect())
            .build();
        try (db) {
            int applied = Schema.ensure(db, IndexedEntity.class);
            assertEquals(1, applied, "table creation must still happen");

            List<String> indexes = db.query(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES WHERE UPPER(TABLE_NAME) = ?",
                "INDEXED_ENTITY"
            ).list(String.class);
            assertTrue(indexes.stream().noneMatch(i -> i.toLowerCase().contains("idx_indexed_entity_email")),
                "the @Index must NOT be created when introspection failed: " + indexes);
        }
    }

    @Test
    void indexIntrospectionFailureSkipsIndexDdlWithoutThrowing() {
        FailingIndexesDialect dialect = new FailingIndexesDialect();
        RecordingDb db = new RecordingDb(dialect);

        int applied = Schema.ensure(db, IndexedEntity.class);
        assertEquals(0, applied);
        assertTrue(db.executedSql.stream().noneMatch(s -> s.contains("CREATE INDEX")),
            "no index DDL may run when index introspection failed: " + db.executedSql);
    }

    @Test
    void columnIntrospectionFailureSkipsColumnAdditions() {
        FailingColumnsDialect dialect = new FailingColumnsDialect();
        RecordingDb db = new RecordingDb(dialect);

        int applied = Schema.ensure(db, IndexedEntity.class);
        assertEquals(0, applied);
        assertTrue(db.executedSql.stream().noneMatch(s -> s.contains("ALTER TABLE")),
            "no ALTER may run when column introspection failed: " + db.executedSql);
    }

    @Test
    void tableIntrospectionFailureSkipsEntireEnsure() {
        FailingTablesDialect dialect = new FailingTablesDialect();
        RecordingDb db = new RecordingDb(dialect);

        int applied = Schema.ensure(db, IndexedEntity.class);
        assertEquals(0, applied);
        assertTrue(db.executedSql.isEmpty(),
            "no DDL may run when table introspection failed: " + db.executedSql);
    }

    /** PostgresDialect without IF NOT EXISTS — forces the existingIndexes (pg_indexes) path. */
    private static final class PgIndexesDialect extends PostgresDialect {

        @Override
        public boolean supportsIndexIfNotExists() {
            return false;
        }
    }

    @Table("indexed_entity")
    record IndexedEntity(@Id Long id, @Index String email) {}

    // ====================== injected-failure dialects ======================

    private static final class FailingIndexesDialect implements Dialect {

        @Override
        public String dialectId() {
            return "test-failing-indexes";
        }

        @Override
        public String quoteName(String name) {
            return name;
        }

        @Override
        public boolean supportsIndexIfNotExists() {
            return false;
        }

        @Override
        public Set<String> existingTables(Database db) {
            return Set.of("indexed_entity");
        }

        @Override
        public Set<String> existingColumns(Database db, String tableName) {
            return Set.of("id", "email");
        }

        @Override
        public Set<String> existingIndexes(Database db, String tableName) {
            throw new SqlException("simulated index introspection failure");
        }

        @Override
        public String generatedClause() {
            return "GENERATED BY DEFAULT AS IDENTITY";
        }

        @Override
        public String defaultUUIDType() {
            return "UUID";
        }
    }

    private static final class FailingColumnsDialect implements Dialect {

        @Override
        public String dialectId() {
            return "test-failing-columns";
        }

        @Override
        public String quoteName(String name) {
            return name;
        }

        @Override
        public Set<String> existingTables(Database db) {
            return Set.of("indexed_entity");
        }

        @Override
        public Set<String> existingColumns(Database db, String tableName) {
            throw new SqlException("simulated column introspection failure");
        }

        @Override
        public String generatedClause() {
            return "GENERATED BY DEFAULT AS IDENTITY";
        }

        @Override
        public String defaultUUIDType() {
            return "UUID";
        }
    }

    private static final class FailingTablesDialect implements Dialect {

        @Override
        public String dialectId() {
            return "test-failing-tables";
        }

        @Override
        public String quoteName(String name) {
            return name;
        }

        @Override
        public Set<String> existingTables(Database db) {
            throw new SqlException("simulated table introspection failure");
        }

        @Override
        public Set<String> existingColumns(Database db, String tableName) {
            return Set.of("id", "email");
        }

        @Override
        public String generatedClause() {
            return "GENERATED BY DEFAULT AS IDENTITY";
        }

        @Override
        public String defaultUUIDType() {
            return "UUID";
        }
    }

    private static final class RecordingDb implements Database {

        private final Dialect dialect;
        private final List<String> executedSql = new ArrayList<>();

        RecordingDb(Dialect dialect) {
            this.dialect = dialect;
        }

        @Override
        public Dialect dialect() {
            return dialect;
        }

        @Override
        public Query query(String sql, Object... params) {
            throw new UnsupportedOperationException("query should not be called");
        }

        @Override
        public ExecuteResult execute(String sql, Object... params) {
            executedSql.add(sql);
            return new ExecuteResult(0, null);
        }

        @Override
        public BatchQuery batch(String sql) {
            throw new UnsupportedOperationException("batch should not be called");
        }

        @Override
        public void transaction(Transactional work) {
            throw new UnsupportedOperationException("transaction should not be called");
        }

        @Override
        public void transaction(IsolationLevel isolation, Transactional work) {
            throw new UnsupportedOperationException("transaction should not be called");
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public DatabaseStats stats() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
