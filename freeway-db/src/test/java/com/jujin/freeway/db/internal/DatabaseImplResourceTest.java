package com.jujin.freeway.db.internal;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.IsolationLevel;
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
                originalIsolation = before.connection().getTransactionIsolation();
            } finally {
                db.pool().release(before);
            }

            IsolationLevel changed = originalIsolation == Connection.TRANSACTION_SERIALIZABLE
                ? IsolationLevel.READ_COMMITTED
                : IsolationLevel.SERIALIZABLE;

            db.transaction(changed, () -> { /* no-op, just test isolation restore */ });

            PooledConnection after = db.pool().borrow();
            try {
                assertEquals(originalIsolation, after.connection().getTransactionIsolation());
            } finally {
                db.pool().release(after);
            }
        }
    }

    private static DatabaseImpl createDb() {
        String dbName = "freeway_isolation_" + UUID.randomUUID().toString().replace('-', '_');
        return new DatabaseImpl(
            PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""),
            new RowMapperResolver(new CoercerDefault(), Map.of(), Map.of())
        );
    }
}
