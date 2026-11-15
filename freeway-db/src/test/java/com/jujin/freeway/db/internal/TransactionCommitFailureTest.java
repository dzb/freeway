package com.jujin.freeway.db.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.db.Pool;
import com.jujin.freeway.db.PoolConfig;
import com.jujin.freeway.db.PoolStats;
import com.jujin.freeway.db.PooledConnection;
import com.jujin.freeway.db.dialect.H2Dialect;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** A failed commit must roll back and release exactly once, not leak the connection. */
class TransactionCommitFailureTest {

    @Test
    void commitFailureRollsBackAndReleasesExactlyOnce() throws Exception {
        Connection real = DriverManager.getConnection(
            "jdbc:h2:mem:commit_failure;DB_CLOSE_DELAY=-1", "sa", "");
        AtomicInteger rolledBack = new AtomicInteger();
        Connection failing = (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("commit")) {
                    throw new SQLException("injected commit failure");
                }
                if (method.getName().equals("rollback")) {
                    rolledBack.incrementAndGet();
                }
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        ErrorCommitPool pool = new ErrorCommitPool(failing);
        DatabaseImpl db = DatabaseImpl.assemble(
            PoolConfig.defaults("jdbc:h2:mem:commit_failure", "sa", ""),
            new RowMapperResolver(new CoercerDefault(), Map.of(), Map.of()),
            pool, new H2Dialect());
        try (db) {
            RuntimeException failure = assertThrows(RuntimeException.class,
                () -> db.transaction(() -> { }));
            assertTrue(failure.getMessage().contains("Commit failed"), failure.getMessage());
            assertEquals(1, rolledBack.get(), "commit failure must attempt rollback");
            assertEquals(1, pool.released.get(), "the borrowed connection must be released once");
            assertEquals(0, pool.invalidated.get(), "a normal restore must not invalidate");
        } finally {
            real.close();
        }
    }

    private static final class ErrorCommitPool implements Pool {
        private final PooledConnection handle;
        final AtomicInteger released = new AtomicInteger();
        final AtomicInteger invalidated = new AtomicInteger();

        ErrorCommitPool(Connection connection) {
            this.handle = () -> connection;
        }

        @Override public PooledConnection borrow() { return handle; }
        @Override public void release(PooledConnection connection) { released.incrementAndGet(); }
        @Override public void invalidate(PooledConnection connection) { invalidated.incrementAndGet(); }
        @Override public PoolStats stats() { return null; }
        @Override public void close() { }
    }
}
