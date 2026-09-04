package com.jujin.freeway.db.internal;

import com.jujin.freeway.db.PooledConnection;

import com.jujin.freeway.commons.coercion.CoercerImpl;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.IsolationLevel;
import java.sql.Connection;
import java.time.Duration;
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

    @Test
    void zeroQueryTimeoutMeansNoTimeout() {
        DatabaseImpl db = createDb(Duration.ZERO);
        try (db) {
            assertEquals(0, db.queryTimeoutSeconds(),
                "queryTimeout 0 must map to JDBC setQueryTimeout(0) = no timeout");
        }
    }

    @Test
    void subSecondQueryTimeoutRoundsUpToWholeSeconds() {
        DatabaseImpl db = createDb(Duration.ofMillis(500));
        try (db) {
            assertEquals(1, db.queryTimeoutSeconds());
        }
    }

    private static DatabaseImpl createDb(Duration queryTimeout) {
        String dbName = "freeway_timeout_" + UUID.randomUUID().toString().replace('-', '_');
        return new DatabaseImpl(
            new PoolConfig(
                "jdbc:h2:mem:" + dbName + ";DB_CLOSE_DELAY=-1", "sa", "",
                1, 0,
                Duration.ofSeconds(5),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                Duration.ofSeconds(30),
                null,
                Duration.ofSeconds(5),
                queryTimeout
            ),
            new RowMapperResolver(new CoercerImpl(), Map.of(), Map.of())
        );
    }

    private static DatabaseImpl createDb() {
        String dbName = "freeway_isolation_" + UUID.randomUUID().toString().replace('-', '_');
        return new DatabaseImpl(
            PoolConfig.defaults("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""),
            new RowMapperResolver(new CoercerImpl(), Map.of(), Map.of())
        );
    }
}
