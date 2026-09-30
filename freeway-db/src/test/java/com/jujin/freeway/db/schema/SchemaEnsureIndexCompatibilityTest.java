package com.jujin.freeway.db.schema;

import com.jujin.freeway.db.BatchQuery;
import com.jujin.freeway.db.Database;
import com.jujin.freeway.db.DatabaseStats;
import com.jujin.freeway.db.ExecuteResult;
import com.jujin.freeway.db.IsolationLevel;
import com.jujin.freeway.db.Query;
import com.jujin.freeway.db.Transactional;
import com.jujin.freeway.db.dialect.Dialect;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaEnsureIndexCompatibilityTest {

    @Test
    void skipsExistingIndexWhenDialectDoesNotSupportIfNotExists() {
        RecordingDatabase db = new RecordingDatabase();

        // dialect comes from the database (RecordingDatabase.dialect())
        int applied = Schema.ensure(db, IndexedEntity.class);

        assertEquals(0, applied);
        assertTrue(db.executedSql.isEmpty(), "no DDL should be executed");
    }

    /** The index-only case: DDL runs, and the change count stays 0. */
    @Test
    void createdIndexIsExecutedButNotCountedAsAChange() {
        IndexMissingDatabase db = new IndexMissingDatabase();

        int applied = Schema.ensure(db, IndexedEntity.class);

        assertEquals(1, db.executedSql.size(), "the missing index is created");
        assertTrue(db.executedSql.get(0).contains("INDEX"), db.executedSql.get(0));
        assertEquals(0, applied,
            "the return value counts schema CHANGES, not statements — a table "
                + "and its columns already existed, so nothing changed. See the "
                + "@return contract on Schema.ensure");
    }

    @Table("indexed_entity")
    record IndexedEntity(@Id Long id, @Index String email) {}

    private static final class NonIdempotentDialect implements Dialect {
        @Override
        public String dialectId() {
            return "test";
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

    /**
     * Table and columns are all present; only the index is missing.
     *
     * <p>Exists to pin the return-value contract from the other side: the
     * CREATE INDEX really is executed here, and the count is still 0. That
     * asymmetry is deliberate — the count answers "did the schema structure
     * change", and an index is the companion of a table rather than a change
     * to it. Before the {@code @return} javadoc said so, the two were easy to
     * read as a bug in either direction.
     */
    private static final class IndexMissingDatabase extends RecordingDatabase {
        @Override
        public Dialect dialect() {
            return new Dialect() {
                @Override
                public String dialectId() {
                    return "test";
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
                    return Set.of();   // the drift under test
                }

                @Override
                public String generatedClause() {
                    return "IDENTITY";
                }

                @Override
                public String defaultUUIDType() {
                    return "UUID";
                }
            };
        }
    }

    private static class RecordingDatabase implements Database {
        final List<String> executedSql = new ArrayList<>();

        @Override
        public Dialect dialect() {
            return new NonIdempotentDialect();
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
