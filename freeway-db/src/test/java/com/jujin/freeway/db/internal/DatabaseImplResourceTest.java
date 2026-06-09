package com.jujin.freeway.db.internal;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.DatabaseConfig;
import com.jujin.freeway.db.IsolationLevel;
import com.jujin.freeway.db.Transaction;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseImplResourceTest {
    @Test
    void transactionCloseRestoresOriginalIsolationLevel() throws Exception {
        DatabaseImpl db = createDb();
        try (db) {
            PooledConnection before = db.pool().borrow();
            int originalIsolation;
            try {
                originalIsolation = before.jdbcConnection().getTransactionIsolation();
            } finally {
                db.pool().release(before);
            }

            IsolationLevel changed = originalIsolation == Connection.TRANSACTION_SERIALIZABLE
                ? IsolationLevel.READ_COMMITTED
                : IsolationLevel.SERIALIZABLE;

            Transaction tx = db.beginTransaction();
            tx.isolation(changed);
            tx.close();

            PooledConnection after = db.pool().borrow();
            try {
                assertEquals(originalIsolation, after.jdbcConnection().getTransactionIsolation());
            } finally {
                db.pool().release(after);
            }
        }
    }

    private static DatabaseImpl createDb() {
        String dbName = "freeway_isolation_" + UUID.randomUUID().toString().replace('-', '_');
        return new DatabaseImpl(
            DatabaseConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""),
            new RowMapperResolver(new CoercerDefault(), Map.of(), Map.of())
        );
    }
}
