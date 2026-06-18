package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.BatchQuery;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.ExecuteResult;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaEnsureIndexCompatibilityTest {

    @Test
    void skipsExistingIndexWhenDialectDoesNotSupportIfNotExists() {
        RecordingDatabase db = new RecordingDatabase();
        Dialect dialect = new NonIdempotentDialect();

        int applied = Schema.ensure(db, dialect, IndexedEntity.class);

        assertEquals(0, applied);
        assertTrue(db.executedSql.isEmpty(), "no DDL should be executed");
    }

    @Table("indexed_entity")
    record IndexedEntity(@Id Long id, @Index String email) {}

    private static final class NonIdempotentDialect implements Dialect {
        @Override
        public String quoteName(String name) {
            return name;
        }

        @Override
        public String createTable(TableDef table) {
            return "CREATE TABLE " + table.name();
        }

        @Override
        public List<String> createIndexes(TableDef table) {
            return List.of("CREATE INDEX idx_indexed_entity_email ON indexed_entity (email)");
        }

        @Override
        public String addColumn(String tableName, ColumnDef column) {
            return "ALTER TABLE " + tableName + " ADD COLUMN " + column.name();
        }

        @Override
        public String dropTable(String tableName) {
            return "DROP TABLE " + tableName;
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
            return Set.of("idx_indexed_entity_email");
        }

        @Override
        public String generatedClause() {
            return "IDENTITY";
        }

        @Override
        public String defaultUUIDType() {
            return "UUID";
        }
    }

    private static final class RecordingDatabase implements Database {
        private final List<String> executedSql = new ArrayList<>();

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
        public void transaction(com.jujin.freeway.db.IsolationLevel isolation, Transactional work) {
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
