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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema DDL must not silently commit a surrounding user transaction on
 * databases without transactional DDL (MySQL/MariaDB). ensure()/drop() refuse
 * to run inside a transaction there; transactional-DDL databases (PostgreSQL,
 * H2, SQLite) keep working inside transactions.
 */
class SchemaTransactionalDdlGuardTest {

    @Test
    void ensureRejectsDdlInsideTransactionOnNonTransactionalDialect() {
        NonTransactionalTestDialect dialect = new NonTransactionalTestDialect();
        RecordingDb db = new RecordingDb(dialect, true);

        SqlException ex = assertThrows(SqlException.class,
            () -> Schema.ensure(db, User.class));
        assertTrue(ex.getMessage().contains("implicitly commit"),
            "message must explain the implicit commit hazard: " + ex.getMessage());
        assertTrue(db.executedSql.isEmpty(),
            "no DDL may run when the guard fires: " + db.executedSql);
    }

    @Test
    void dropRejectsDdlInsideTransactionOnNonTransactionalDialect() {
        NonTransactionalTestDialect dialect = new NonTransactionalTestDialect();
        RecordingDb db = new RecordingDb(dialect, true);

        SqlException ex = assertThrows(SqlException.class,
            () -> Schema.drop(db, User.class));
        assertTrue(ex.getMessage().contains("implicitly commit"), ex.getMessage());
        assertTrue(db.executedSql.isEmpty(), "no DDL may run when the guard fires");
    }

    @Test
    void ensureAllowsDdlInsideTransactionOnTransactionalDialect() {
        TransactionalTestDialect dialect = new TransactionalTestDialect();
        RecordingDb db = new RecordingDb(dialect, true);

        int applied = Schema.ensure(db, User.class);
        assertEquals(1, applied);
        assertTrue(db.executedSql.stream().anyMatch(s -> s.contains("CREATE TABLE")),
            "transactional-DDL dialect must allow ensure inside a transaction: "
                + db.executedSql);
    }

    @Test
    void ensureAllowsDdlOutsideTransactionOnNonTransactionalDialect() {
        NonTransactionalTestDialect dialect = new NonTransactionalTestDialect();
        RecordingDb db = new RecordingDb(dialect, false);

        int applied = Schema.ensure(db, User.class);
        assertEquals(1, applied, "ensure outside a transaction is always allowed");
    }

    @Test
    void ensureInsideTransactionWorksOnH2PostgresMode() {
        // H2 in PostgreSQL mode has transactional DDL — wrapping ensure() in a
        // user transaction is safe and commits atomically.
        String dbName = "fw_schema_tx_" + UUID.randomUUID().toString().replace('-', '_');
        Database db = new DatabaseBuilder()
            .config(PoolConfig.defaults(
                "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""))
            .build();
        try (db) {
            db.transaction(() -> {
                int applied = Schema.ensure(db, User.class);
                assertEquals(1, applied);
            });

            List<String> tables = db.query(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
            ).list(String.class);
            assertTrue(tables.stream().anyMatch(t -> t.equalsIgnoreCase("users")),
                "table must exist after the committed transaction: " + tables);
        }
    }

    @Table("users")
    public record User(@Id Long id, String name) {}

    // ====================== stubs ======================

    /** MySQL-like dialect: no transactional DDL, fixed introspection data. */
    private static class NonTransactionalTestDialect implements Dialect {

        @Override
        public String dialectId() {
            return "test-nontransactional";
        }

        @Override
        public String quoteName(String name) {
            return name;
        }

        @Override
        public boolean supportsTransactionalDdl() {
            return false;
        }

        @Override
        public Set<String> existingTables(Database db) {
            return Set.of();
        }

        @Override
        public Set<String> existingColumns(Database db, String tableName) {
            return Set.of("id", "name");
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

    private static final class TransactionalTestDialect extends NonTransactionalTestDialect {

        @Override
        public String dialectId() {
            return "test-transactional";
        }

        @Override
        public boolean supportsTransactionalDdl() {
            return true;
        }
    }

    private static final class RecordingDb implements Database {

        private final Dialect dialect;
        private final boolean inTx;
        private final List<String> executedSql = new ArrayList<>();

        RecordingDb(Dialect dialect, boolean inTx) {
            this.dialect = dialect;
            this.inTx = inTx;
        }

        @Override
        public Dialect dialect() {
            return dialect;
        }

        @Override
        public boolean inTransaction() {
            return inTx;
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
